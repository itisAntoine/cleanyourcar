"""Typed previews and fail-closed, target-bound installation/debloat execution."""
import copy
import hashlib
import hmac
import json
import os
import re
import shutil
import subprocess
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path

from .adb import AdbError, TargetIdentity, environment_key
from .profiles import (PolicyError, candidate_list_hash, profile_hash,
                       discover, DiscoveryError, validate_candidates,
                       validate_debloat_policy, validate_profile_object,
                       _check_exact_match, _home_packages)


class PlanError(ValueError):
    """A safe plan or a target-bound confirmation cannot be established."""


# These are release trust boundaries, not caller-supplied qualification assertions.
_TRUSTED_INSTALL_QUALIFICATIONS = frozenset()
SUPPORTED_SETTINGS = frozenset()
_PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+\Z")
_HASH = re.compile(r"[0-9a-f]{64}\Z")
_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,95}\Z")


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True,
                      allow_nan=False).encode("utf-8")


def content_hash(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def validate_target(target):
    if (not isinstance(target, TargetIdentity)
            or not _HASH.fullmatch(target.binding_hash)
            or not _HASH.fullmatch(target.firmware_hash)
            or type(target.android_user) is not int or target.android_user < 0):
        raise PlanError("invalid target identity")


def check_report(report, target=None):
    validate_target(report.identity)
    if (report.read_only or report.identity.android_user != report.android_user
            or environment_key(report.environment) != report.identity.firmware_hash
            or set(report.unresolved_facts) & {
                "android_user", "target_identity", "platform", "packages", "packages.unparsed"}):
        raise PlanError("required target/user/firmware/package facts are unresolved")
    if target is not None and report.identity != target:
        raise PlanError("record/plan belongs to another target, firmware or Android user")


def exact_flag(report, package, allow_absent=False):
    if "package_flags." + package in report.unresolved_facts:
        raise PlanError("%s original enabled flag is explicitly unresolved" % package)
    if package not in report.packages:
        if allow_absent:
            return None
        raise PlanError("%s is not installed for this user" % package)
    flag = report.package_flags.get(package)
    if type(flag) is not int or flag not in range(5):
        raise PlanError("%s has no exact original enabled flag" % package)
    return flag


def validate_action(action):
    if not isinstance(action, dict):
        raise PlanError("action must be a typed object")
    kind = action.get("type")
    fields = {
        "SET_PACKAGE_ENABLED_STATE": {"type", "package", "value"},
        "INSTALL_APK": {"type", "package", "version_code", "min_sdk", "sha256", "path"},
        "UNINSTALL_FRESH_APK": {"type", "package", "version_code"},
        "SET_SETTING": {"type", "namespace", "name", "value"},
    }
    if kind not in fields or set(action) != fields[kind]:
        raise PlanError("unknown action type or fields (shell templates are prohibited)")
    if kind == "SET_SETTING":
        if (action["namespace"], action["name"]) not in SUPPORTED_SETTINGS:
            raise PlanError("setting has no explicitly supported write/recovery route")
        if action["value"] is not None and not isinstance(action["value"], str):
            raise PlanError("setting value must be an exact string or explicit absence")
    else:
        if not isinstance(action["package"], str) or not _PACKAGE.fullmatch(action["package"]):
            raise PlanError("invalid package name")
        if kind == "SET_PACKAGE_ENABLED_STATE":
            if type(action["value"]) is not int or action["value"] not in range(5):
                raise PlanError("invalid enabled flag")
        elif kind == "UNINSTALL_FRESH_APK":
            if type(action["version_code"]) is not int or action["version_code"] <= 0:
                raise PlanError("invalid fresh APK removal version")
        elif (type(action["version_code"]) is not int or action["version_code"] <= 0
              or type(action["min_sdk"]) is not int or action["min_sdk"] <= 0
              or not isinstance(action["sha256"], str) or not _HASH.fullmatch(action["sha256"])
              or not isinstance(action["path"], str) or not action["path"]
              or any(ord(c) < 32 for c in action["path"])):
            raise PlanError("invalid APK identity/SDK/artifact metadata")


@dataclass(frozen=True)
class OperationPlan:
    operation_id: str
    operation: str
    target: TargetIdentity
    profile_hash: str
    list_hash: str
    artifact_hash: str
    preconditions: dict
    actions: tuple
    recovery_requirements: tuple
    limitations: tuple
    profile_id: str = ""
    schema_version: int = 1

    @property
    def android_user(self):
        return self.target.android_user

    def validate(self):
        validate_target(self.target)
        if (type(self.schema_version) is not int or self.schema_version != 1
                or not isinstance(self.operation_id, str) or not _ID.fullmatch(self.operation_id)
                or self.operation not in {"debloat", "install", "remove", "restore"}):
            raise PlanError("invalid plan version, operation or ID")
        for value in (self.profile_hash, self.list_hash, self.artifact_hash):
            if not isinstance(value, str) or (value and not _HASH.fullmatch(value)):
                raise PlanError("invalid profile/list/artifact hash")
        for action in self.actions:
            validate_action(action)
        canonical(self.to_dict())

    def to_dict(self):
        data = asdict(self)
        data["actions"] = list(self.actions)
        data["recovery_requirements"] = list(self.recovery_requirements)
        data["limitations"] = list(self.limitations)
        return copy.deepcopy(data)

    @classmethod
    def from_dict(cls, data):
        try:
            values = copy.deepcopy(data)
            values["target"] = TargetIdentity(**values["target"])
            for key in ("actions", "recovery_requirements", "limitations"):
                values[key] = tuple(values[key])
            plan = cls(**values)
            plan.validate()
            return plan
        except (TypeError, KeyError, ValueError) as error:
            raise PlanError("invalid saved plan: %s" % error) from error

    @property
    def plan_hash(self):
        self.validate()
        return content_hash(self.to_dict())

    def confirmation_hash(self, record):
        if record.target != self.target or record.operation_id != self.operation_id:
            raise PlanError("confirmation record is for another operation/target")
        if record.operation_hash != self.plan_hash:
            raise PlanError("plan changed after original state was saved")
        return content_hash({"plan_hash": self.plan_hash, "record_hash": record.record_hash,
                             "target": asdict(self.target)})


def verify_confirmation(plan, record, confirmation_hash):
    expected = plan.confirmation_hash(record)
    if not isinstance(confirmation_hash, str) or not hmac.compare_digest(expected, confirmation_hash):
        raise PlanError("confirmation does not match the exact plan and saved originals")


def _preconditions(report, packages):
    return {"package_flags": {p: exact_flag(report, p, allow_absent=True) for p in packages},
            "installed_packages": {p: copy.deepcopy(report.packages.get(p)) for p in packages},
            "home_candidates": copy.deepcopy(report.home_candidates),
            "unresolved_facts": list(report.unresolved_facts)}


_STATE_RESTORE_COMMANDS = {
    0: ("default_state", "default-state"),
    1: ("enable", "enable"),
    2: ("disable", "disable"),
    3: ("disable_user", "disable-user"),
    4: ("disable_until_used", "disable-until-used"),
}


def _state_restore_command(report, flag):
    capability, command = _STATE_RESTORE_COMMANDS[flag]
    if report.command_capabilities.get(capability) is not True:
        raise PlanError("exact original enabled flag %d has no probed restore command" % flag)
    return command


def _plan(operation, report, profile, actions, list_hash="", artifact_hash="", limitations=()):
    return OperationPlan(
        operation_id=uuid.uuid4().hex, operation=operation, target=report.identity,
        profile_hash=profile_hash(profile) if profile else "", list_hash=list_hash,
        artifact_hash=artifact_hash, profile_id=profile["profileId"] if profile else "",
        preconditions=_preconditions(report, [a["package"] for a in actions if "package" in a]),
        actions=tuple(copy.deepcopy(actions)),
        recovery_requirements=("durable originals", "exclusive target lock",
                               "fresh identity and preconditions", "explicit confirmation",
                               "intent before command; verified readback after command"),
        limitations=tuple(limitations) + (
            "Parked-only setup; no verified universal driving-state detector.",
            "Restore and fresh APK removal require separate previews and confirmations.",
            "Records are not firmware or application-data backups.",
            "HOME and permission changes require separate on-device approval.",))


def plan_debloat(report, profile, policy, candidates):
    candidates = validate_candidates(candidates)
    validate_debloat_policy(report, profile, policy, candidates)
    check_report(report)
    for package in candidates:
        _state_restore_command(report, exact_flag(report, package))
    actions = [{"type": "SET_PACKAGE_ENABLED_STATE", "package": p, "value": 3}
               for p in candidates]
    plan = _plan("debloat", report, profile, actions, candidate_list_hash(candidates),
                 limitations=("Unresolved facts: " + ", ".join(report.unresolved_facts),))
    return _with_qualification(plan, {"profile": profile, "policy": policy})


def plan_install(report, profile, apk_metadata):
    check_report(report)
    try:
        profile = validate_profile_object(profile, 2147483647)
        _check_exact_match(report.environment, profile)
        _home_packages(report)
        action = dict(apk_metadata, type="INSTALL_APK")
        validate_action(action)
    except (PolicyError, TypeError) as error:
        raise PlanError(str(error)) from error
    key = (report.identity.firmware_hash, profile_hash(profile), action["package"])
    if key not in _TRUSTED_INSTALL_QUALIFICATIONS:
        raise PlanError("no trusted bundled installation qualification for this firmware/package")
    if action["min_sdk"] > report.environment["sdk"]:
        raise PlanError("APK requires a newer actual SDK")
    exact_flag(report, action["package"], allow_absent=True)
    if action["package"] in report.packages:
        raise PlanError("existing installation: compatible data-preserving APK rollback is unverified")
    action = inspect_apk(action)
    plan = _plan("install", report, profile, [action], artifact_hash=action["sha256"],
                 limitations=("Only fresh installs; global package absence is rechecked by apply.",
                              "Foreground launch must be verified separately on-device.",
                              "New APK removal needs a separate preview and confirmation.",
                              "Optional companion is a separate qualified installation.",
                              "Unresolved facts: " + ", ".join(report.unresolved_facts)))
    return _with_qualification(plan, {"profile": profile})


def _with_qualification(plan, proof):
    return OperationPlan(**dict(plan.__dict__, preconditions=dict(
        plan.preconditions, write_qualification=copy.deepcopy(proof))))


def find_aapt2():
    """Find an existing SDK tool only. Never download tools or invoke Gradle/devices."""
    explicit = os.environ.get("AAPT2")
    if explicit:
        path = Path(explicit).expanduser()
        return str(path.resolve()) if path.is_file() and os.access(path, os.X_OK) else None
    roots = [os.environ.get(name) for name in ("ANDROID_SDK_ROOT", "ANDROID_HOME")]
    properties = Path(__file__).resolve().parents[2] / "local.properties"
    if properties.is_file():
        for line in properties.read_text(encoding="utf-8").splitlines():
            if line.startswith("sdk.dir="):
                roots.append(line[len("sdk.dir="):].replace("\\:", ":").replace("\\\\", "\\"))
    roots.extend((str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk")))
    for root in roots:
        if not root:
            continue
        tools = Path(root).expanduser() / "build-tools"
        candidates = [p for p in tools.glob("*/aapt2")
                      if re.fullmatch(r"[0-9]+(?:\.[0-9]+)*", p.parent.name)]
        candidates.sort(key=lambda p: tuple(int(v) for v in p.parent.name.split(".")), reverse=True)
        for path in candidates:
            if path.is_file() and os.access(path, os.X_OK):
                return str(path.resolve())
    path = shutil.which("aapt2")
    return str(Path(path).resolve()) if path else None


def _artifact_hash(path):
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        if not path.is_file():
            raise PlanError("APK must be an actual local regular file")
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_apk(action):
    """Verify caller metadata against actual local bytes and bounded aapt2 badging."""
    validate_action(action)
    try:
        path = Path(action["path"]).expanduser().resolve(strict=True)
        if not path.is_file() or _artifact_hash(path) != action["sha256"]:
            raise PlanError("actual local APK hash does not match the plan")
        tool = find_aapt2()
        if not tool:
            raise PlanError("existing SDK aapt2 not found; installation is refused")
        result = subprocess.run([tool, "dump", "badging", str(path)],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=30, check=False, shell=False,
                                universal_newlines=True, encoding="utf-8", errors="strict")
        package_lines = [line for line in result.stdout.splitlines() if line.startswith("package:")]
        sdk_lines = [line for line in result.stdout.splitlines() if line.startswith("sdkVersion:")]
        package = (re.fullmatch(r"package: name='([^']+)' versionCode='([0-9]+)'(?: .*)?",
                                package_lines[0]) if len(package_lines) == 1 else None)
        sdk = (re.fullmatch(r"sdkVersion:'([0-9]+)'", sdk_lines[0])
               if len(sdk_lines) == 1 else None)
        if (result.returncode != 0 or result.stderr.strip() or package is None or sdk is None
                or package.group(1) != action["package"]
                or int(package.group(2)) != action["version_code"]
                or int(sdk.group(1)) != action["min_sdk"]
                or _artifact_hash(path) != action["sha256"]):
            raise PlanError("aapt2 did not verify exact APK package/version/minSdk/hash")
        return dict(action, path=str(path))
    except (OSError, subprocess.SubprocessError, UnicodeError) as error:
        raise PlanError("local APK verification unavailable: %s" % type(error).__name__) from error


def describe_apk(path):
    """Read package/versionCode/minSdk/hash of a local APK with the existing SDK aapt2."""
    try:
        resolved = Path(path).expanduser().resolve(strict=True)
        digest = _artifact_hash(resolved)
        tool = find_aapt2()
        if not tool:
            raise PlanError("existing SDK aapt2 not found")
        result = subprocess.run([tool, "dump", "badging", str(resolved)],
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=30, check=False, shell=False,
                                universal_newlines=True, encoding="utf-8", errors="strict")
        lines = result.stdout.splitlines()
        package_lines = [line for line in lines if line.startswith("package:")]
        sdk_lines = [line for line in lines if line.startswith("sdkVersion:")]
        package = (re.fullmatch(r"package: name='([^']+)' versionCode='([0-9]+)'(?: .*)?",
                                package_lines[0]) if len(package_lines) == 1 else None)
        sdk = (re.fullmatch(r"sdkVersion:'([0-9]+)'", sdk_lines[0])
               if len(sdk_lines) == 1 else None)
        if result.returncode != 0 or result.stderr.strip() or package is None or sdk is None:
            raise PlanError("aapt2 could not read exact APK package/version/minSdk")
        return {"package": package.group(1), "version_code": int(package.group(2)),
                "min_sdk": int(sdk.group(1)), "sha256": digest, "path": str(resolved)}
    except (OSError, subprocess.SubprocessError, UnicodeError) as error:
        raise PlanError("local APK description unavailable: %s" % type(error).__name__) from error


@dataclass(frozen=True)
class ApplyResult:
    status: str
    operation_id: str
    actions: tuple
    recovery_references: tuple
    error: str = ""
    recovery_instructions: tuple = (
        "Stop; inspect the private snapshot and intent/readback journal on the same target/user.",
        "Do not replay an install/debloat operation or use legacy scripts as a fallback.",
        "Restore and fresh APK removal require their own exact preview and confirmation.",
        "Restore retry requires fresh exact readback and the same saved confirmation.",
        "Foreground, camera and vehicle checks require separate authorized parked validation.",
    )

    def to_dict(self):
        return asdict(self)


def _qualification(plan, report):
    proof = plan.preconditions.get("write_qualification")
    if not isinstance(proof, dict) or plan.operation not in {"debloat", "install"}:
        raise PlanError("saved plan has no supported write qualification; create a fresh preview")
    keys = {"profile", "policy"} if plan.operation == "debloat" else {"profile"}
    if set(proof) != keys or not plan.actions:
        raise PlanError("missing or invalid plan qualification proof")
    profile = validate_profile_object(proof["profile"], 2147483647)
    if profile_hash(profile) != plan.profile_hash or profile["profileId"] != plan.profile_id:
        raise PlanError("qualification profile no longer binds this plan")
    _check_exact_match(report.environment, profile)
    _home_packages(report)
    packages = [action["package"] for action in plan.actions]
    if len(set(packages)) != len(packages):
        raise PlanError("duplicate action package")
    if plan.operation == "debloat":
        if (plan.artifact_hash or any(action["type"] != "SET_PACKAGE_ENABLED_STATE"
                                    or action["value"] != 3 for action in plan.actions)):
            raise PlanError("debloat permits disable-user only")
        if candidate_list_hash(packages) != plan.list_hash:
            raise PlanError("candidate action list hash changed")
        validate_debloat_policy(report, profile, proof["policy"], packages)
        if not report.command_capabilities.get("disable_user"):
            raise PlanError("firmware has no demonstrated disable-user command")
    else:
        if len(plan.actions) != 1 or plan.actions[0]["type"] != "INSTALL_APK" or plan.list_hash:
            raise PlanError("only one separately qualified fresh APK install is supported")
        action = plan.actions[0]
        key = (plan.target.firmware_hash, plan.profile_hash, action["package"])
        if key not in _TRUSTED_INSTALL_QUALIFICATIONS:
            raise PlanError("installation qualification is absent or revoked")
        if action["sha256"] != plan.artifact_hash or action["min_sdk"] > report.environment["sdk"]:
            raise PlanError("APK artifact/actual SDK no longer matches")
        if inspect_apk(action) != action:
            raise PlanError("APK path/metadata changed since the preview")


def _originals(plan, record):
    before = plan.preconditions
    packages = {a["package"] for a in plan.actions}
    expected_preconditions = {"package_flags", "installed_packages", "home_candidates",
                              "unresolved_facts"}
    if plan.operation in {"debloat", "install"}:
        expected_preconditions.add("write_qualification")
    elif plan.operation == "restore":
        expected_preconditions.update({"source_operation_id", "source_record_hash",
                                       "source_event_references", "source_recoveries",
                                       "restore_before_flags", "restore_blockers",
                                       "source_package_states"})
    elif plan.operation == "remove":
        expected_preconditions.update({"source_operation_id", "source_record_hash",
                                       "source_event_references", "remove_before_flags",
                                       "remove_before_installed"})
    if (set(before) != expected_preconditions
            or set(before["package_flags"]) != packages
            or (plan.operation == "restore"
                and (set(before["restore_before_flags"]) != packages
                     or not isinstance(before["restore_blockers"], list)
                     or any(not isinstance(item, str) for item in before["restore_blockers"])))
            or (plan.operation == "remove"
                and (set(before["remove_before_flags"]) != packages
                     or set(before["remove_before_installed"]) != packages))
            or set(before["installed_packages"]) != packages
            or record.installed_package_identities != before["installed_packages"]
            or record.original_home != before["home_candidates"]
            or record.original_settings):
        raise PlanError("plan does not bind the exact complete anchored originals")
    flags = {p: flag for p, flag in before["package_flags"].items() if flag is not None}
    if record.original_package_flags != flags:
        raise PlanError("planned before flags differ from anchored earliest originals")
    if plan.operation == "install":
        package = plan.actions[0]["package"]
        if before["installed_packages"][package] is not None or before["package_flags"][package] is not None:
            raise PlanError("updates have no demonstrated data-preserving rollback route")
    if plan.operation == "remove":
        package = plan.actions[0]["package"]
        if (len(plan.actions) != 1 or plan.actions[0]["type"] != "UNINSTALL_FRESH_APK"
                or before["installed_packages"][package] is not None
                or before["package_flags"][package] is not None
                or type(before["remove_before_flags"][package]) is not int
                or before["remove_before_flags"][package] not in range(5)
                or not isinstance(before["remove_before_installed"][package], dict)):
            raise PlanError("fresh APK removal does not preserve the anchored absent original")


def _fresh_state(plan, report, completed):
    check_report(report, plan.target)
    if (report.home_candidates != plan.preconditions["home_candidates"]
            or report.unresolved_facts != plan.preconditions["unresolved_facts"]):
        raise PlanError("HOME ownership or required read preconditions changed")
    for index, action in enumerate(plan.actions):
        package = action["package"]
        if action["type"] == "INSTALL_APK" and index in completed:
            info = report.packages.get(package)
            flag = exact_flag(report, package)
            if (info is None or info.get("version_code") != action["version_code"]
                    or info.get("system") is not False or flag not in (0, 1)
                    or "packages.%s.metadata" % package in report.unresolved_facts):
                raise PlanError("installed APK per-user identity/enabled state is not verified")
            continue
        if report.packages.get(package) != plan.preconditions["installed_packages"][package]:
            raise PlanError("installed package identity changed: " + package)
        flag = exact_flag(report, package, allow_absent=action["type"] == "INSTALL_APK")
        expected = action["value"] if index in completed else plan.preconditions["package_flags"][package]
        if flag != expected:
            raise PlanError("exact per-user enabled state changed: " + package)


def _restore_fresh_state(plan, report, completed):
    check_report(report, plan.target)
    if (report.home_candidates != plan.preconditions["home_candidates"]
            or report.unresolved_facts != plan.preconditions["unresolved_facts"]):
        raise PlanError("HOME ownership or required read preconditions changed")
    for index, action in enumerate(plan.actions):
        package = action["package"]
        if (report.packages.get(package) != plan.preconditions["installed_packages"][package]
                or "packages.%s.metadata" % package in report.unresolved_facts):
            raise PlanError("installed package identity changed: " + package)
        current = exact_flag(report, package)
        before = plan.preconditions["restore_before_flags"][package]
        if type(before) is not int:
            raise PlanError("restore plan lacks exact current before flag: " + package)
        expected = action["value"] if index in completed else None
        if expected is not None:
            if current != expected:
                raise PlanError("restored package state changed externally: " + package)
        elif current not in (before, action["value"]):
            raise PlanError("package state conflicts with restore preview: " + package)
    restore_indices = {action["package"]: index
                       for index, action in enumerate(plan.actions)}
    for package, state in plan.preconditions["source_package_states"].items():
        if not isinstance(state, dict) or set(state) != {
                "type", "identity", "current_identity", "current_flag", "original_flag"}:
            raise PlanError("restore plan lacks complete source package state")
        installed_identity = (state["identity"] if state["type"] == "SET_PACKAGE_ENABLED_STATE"
                              else state["current_identity"])
        if report.packages.get(package) != installed_identity:
            raise PlanError("source package installation identity changed: " + package)
        current = exact_flag(report, package, allow_absent=state["type"] == "INSTALL_APK")
        if state["type"] == "INSTALL_APK":
            if current != state["current_flag"]:
                raise PlanError("source installation state changed since restore preview: " + package)
            continue
        if package not in restore_indices:
            if current != state["current_flag"]:
                raise PlanError("package already restored state changed externally: " + package)
        elif restore_indices[package] in completed:
            if current != state["original_flag"]:
                raise PlanError("restored package state changed externally: " + package)
        elif current not in (state["current_flag"], state["original_flag"]):
            raise PlanError("source package state conflicts with restore preview: " + package)


def _restore_accepted(action, command):
    expected = {
        0: "default",
        1: "enabled",
        2: "disabled",
        3: "disabled-user",
        4: "disabled-until-used",
    }[action["value"]]
    return (command.returncode == 0 and not command.stderr.strip()
            and re.fullmatch(r"(?:Package )?" + re.escape(action["package"])
                             + r" new state: " + re.escape(expected),
                             command.stdout.strip()) is not None)


def _source_prefix_matches(source, plan):
    expected = tuple(plan.preconditions["source_event_references"])
    actual = source.event_references
    if len(actual) < len(expected) or actual[:len(expected)] != expected:
        raise PlanError("source journal no longer matches the restore preview")
    suffix = source.events[len(expected):]
    recoveries = {item["action_index"]: item for item in plan.preconditions["source_recoveries"]}
    patterns = {}
    for index, recovery in recoveries.items():
        anchor_index = next((position for position, entry in enumerate(source.events[:len(expected)])
                             if entry["hash"] == recovery["intent_reference"]), None)
        if anchor_index is None:
            raise PlanError("source recovery evidence does not anchor to its journal prefix")
        anchor = source.events[anchor_index]["event"]
        if anchor["action_index"] != index:
            raise PlanError("source recovery evidence references another action")
        patterns[index] = (["readback"] if anchor["kind"] == "intent"
                            else ["intent", "readback"] if (
                                anchor["kind"] == "readback" and not anchor["confirmed"])
                            else [])
    position = 0
    counts = {index: 0 for index in recoveries}
    while position < len(suffix):
        entry = suffix[position]
        event = entry["event"]
        index = event["action_index"]
        recovery = recoveries.get(index)
        if recovery is None:
            raise PlanError("unexpected source journal extension since restore preview")
        pattern = patterns[index]
        offset = counts[index]
        if offset >= len(pattern) or event["kind"] != pattern[offset]:
            raise PlanError("source journal extension is not an allowed recovery record")
        if event["kind"] == "intent":
            position += 1
            if position >= len(suffix):
                raise PlanError("source reconciliation intent is still pending")
            result = suffix[position]["event"]
            if (result["kind"] != "readback" or result["action_index"] != index
                    or result["observed"] != recovery["observed"]
                    or result["confirmed"] != recovery["confirmed"]):
                raise PlanError("source reconciliation differs from fresh restore evidence")
            counts[index] += 2
            position += 1
        elif (event["kind"] != "readback"
              or event["observed"] != recovery["observed"]
              or event["confirmed"] != recovery["confirmed"]):
            raise PlanError("source reconciliation differs from fresh restore evidence")
        else:
            counts[index] += 1
            position += 1
    if any(count not in (0, len(patterns[index])) for index, count in counts.items()):
        raise PlanError("source recovery journal contains a partial reconciliation")
    return True


def _reconcile_source(plan, client, store):
    source_id = plan.preconditions["source_operation_id"]
    for recovery in plan.preconditions["source_recoveries"]:
        source = store.load(source_id)
        latest = _record_latest_events(source).get(recovery["action_index"])
        observed_report = discover(client)
        check_report(observed_report, plan.target)
        action = source.plan.actions[recovery["action_index"]]
        observed = _observed(action, observed_report)
        if observed != recovery["observed"]:
            raise PlanError("fresh source readback changed since restore preview")
        if latest and latest["event"]["kind"] == "readback":
            event = latest["event"]
            if (event["observed"] == recovery["observed"]
                    and event["confirmed"] == recovery["confirmed"]):
                continue
        if latest and latest["event"]["kind"] == "intent":
            if latest["hash"] != recovery["intent_reference"]:
                raise PlanError("pending source intent differs from restore preview")
        else:
            store.append_event(source_id, {"kind": "intent",
                                           "action_index": recovery["action_index"]})
            observed_report = discover(client)
            check_report(observed_report, plan.target)
            if _observed(action, observed_report) != recovery["observed"]:
                raise PlanError("source state changed during reconciliation")
            observed = _observed(action, observed_report)
        store.append_event(source_id, {
            "kind": "readback", "action_index": recovery["action_index"],
            "observed": observed, "confirmed": recovery["confirmed"]})
    source = store.load(source_id)
    _source_prefix_matches(source, plan)


def _apply_restore(plan, client, store, confirmation_hash):
    from .records import RecordError

    errors = (PlanError, RecordError, AdbError, DiscoveryError, PolicyError,
              OSError, ValueError, TypeError, KeyError)
    operation_id = plan.operation_id
    results = []
    references = [str(store.root / operation_id / name)
                  for name in ("snapshot.json", "events.jsonl")]
    attempted = False
    verified = 0
    completed = set()
    try:
        if client.android_user != plan.android_user:
            raise PlanError("client Android user differs from the restore plan")
        for index, action in enumerate(plan.actions):
            results.append({
                "action_index": index, "action": copy.deepcopy(action),
                "expected_before": {"enabled_flag":
                                    plan.preconditions["restore_before_flags"][action["package"]]},
                "observed_before": None,
                "expected_after": {"enabled_flag": action["value"]},
                "observed_after": None, "confirmed": False, "attempted": False,
                "recovery_reference": {"operation_id": operation_id,
                                       "snapshot": references[0]}})
        with store.lock(plan.target):
            own = store.load(operation_id)
            verify_confirmation(plan, own, confirmation_hash)
            _originals(plan, own)
            source = store.load(plan.preconditions["source_operation_id"])
            if (source.target != plan.target
                    or source.record_hash != plan.preconditions["source_record_hash"]
                    or source.plan.operation == "restore"
                    or source.journal_torn):
                raise PlanError("source record is missing, invalid, torn or belongs to another operation")
            _validate_restore_source(plan, source)
            _source_prefix_matches(source, plan)
            if plan.preconditions["restore_blockers"]:
                raise PlanError("; ".join(plan.preconditions["restore_blockers"]))
            _reconcile_source(plan, client, store)
            report = discover(client)
            _restore_fresh_state(plan, report, completed)
            if client.identity() != plan.target or client.android_user != plan.android_user:
                raise PlanError("target/firmware/user changed before restore completion")
            for index, action in enumerate(plan.actions):
                own = store.load(operation_id)
                verify_confirmation(plan, own, confirmation_hash)
                _originals(plan, own)
                source = store.load(plan.preconditions["source_operation_id"])
                if (source.record_hash != plan.preconditions["source_record_hash"]
                        or source.journal_torn):
                    raise PlanError("source snapshot changed or its journal became torn")
                _validate_restore_source(plan, source)
                _source_prefix_matches(source, plan)
                report = discover(client)
                _restore_fresh_state(plan, report, completed)
                if client.identity() != plan.target or client.android_user != plan.android_user:
                    raise PlanError("target/firmware/user changed before restore")
                package = action["package"]
                current = exact_flag(report, package)
                item = results[index]
                item["observed_before"] = {"enabled_flag": current}
                action_events = [entry["event"] for entry in own.events
                                 if entry["event"]["action_index"] == index]
                latest = action_events[-1] if action_events else None
                if latest and latest["kind"] == "readback" and latest["confirmed"]:
                    if current != action["value"]:
                        raise PlanError("confirmed restore result conflicts with fresh readback")
                    completed.add(index)
                    item["observed_after"] = {"enabled_flag": current}
                    item["confirmed"] = True
                    verified += 1
                    continue
                pending = bool(latest and latest["kind"] == "intent")
                if pending and current == action["value"]:
                    verified += 1
                    reference = store.append_event(operation_id, {
                        "kind": "readback", "action_index": index,
                        "observed": {"enabled_flag": current}, "confirmed": True})
                    item["recovery_reference"]["readback"] = reference
                    own = store.load(operation_id)
                    verify_confirmation(plan, own, confirmation_hash)
                    _originals(plan, own)
                    if own.journal_torn or own.events[-1]["hash"] != reference:
                        raise RecordError("restore readback durability/binding failed")
                    item["observed_after"] = {"enabled_flag": current}
                    item["confirmed"] = True
                    completed.add(index)
                    continue
                if current == action["value"]:
                    if latest is None:
                        raise PlanError("package state changed after restore preview")
                    if not pending:
                        store.append_event(operation_id, {"kind": "intent",
                                                          "action_index": index})
                    verified += 1
                    reference = store.append_event(operation_id, {
                        "kind": "readback", "action_index": index,
                        "observed": {"enabled_flag": current}, "confirmed": True})
                    item["recovery_reference"]["readback"] = reference
                    own = store.load(operation_id)
                    verify_confirmation(plan, own, confirmation_hash)
                    _originals(plan, own)
                    if own.journal_torn or own.events[-1]["hash"] != reference:
                        raise RecordError("restore readback durability/binding failed")
                    item["observed_after"] = {"enabled_flag": current}
                    item["confirmed"] = True
                    completed.add(index)
                    continue
                before = plan.preconditions["restore_before_flags"][package]
                if current != before:
                    raise PlanError("external package state change conflicts with restore: " + package)
                intent_reference = None
                if not pending:
                    intent_reference = store.append_event(
                        operation_id, {"kind": "intent", "action_index": index})
                    item["recovery_reference"]["intent"] = intent_reference
                # The durable intent may take time; re-establish identity, capability,
                # installed identity and exact before-state immediately before the write.
                own = store.load(operation_id)
                verify_confirmation(plan, own, confirmation_hash)
                _originals(plan, own)
                source = store.load(plan.preconditions["source_operation_id"])
                if (source.record_hash != plan.preconditions["source_record_hash"]
                        or source.journal_torn):
                    raise PlanError("source record changed after durable restore intent")
                _source_prefix_matches(source, plan)
                report = discover(client)
                _restore_fresh_state(plan, report, completed)
                if exact_flag(report, package) != before:
                    raise PlanError("package state changed after durable restore intent")
                command_name = _state_restore_command(report, action["value"])
                if client.identity() != plan.target or client.android_user != plan.android_user:
                    raise PlanError("target/firmware/user changed immediately before restore command")
                item["observed_before"] = {"enabled_flag": before}
                attempted = item["attempted"] = True
                command = client.shell(["pm", command_name, "--user",
                                        str(plan.android_user), package])
                after = discover(client)
                check_report(after, plan.target)
                if client.identity() != plan.target or client.android_user != plan.android_user:
                    raise PlanError("target/firmware/user changed during restore readback")
                _restore_fresh_state(plan, after, completed | {index})
                actual = exact_flag(after, package)
                observed = {"enabled_flag": actual}
                item["observed_after"] = observed
                accepted = actual == action["value"] and _restore_accepted(action, command)
                own = store.load(operation_id)
                verify_confirmation(plan, own, confirmation_hash)
                _originals(plan, own)
                source = store.load(plan.preconditions["source_operation_id"])
                if (source.record_hash != plan.preconditions["source_record_hash"]
                        or source.journal_torn):
                    raise PlanError("source record changed during restore action")
                _source_prefix_matches(source, plan)
                if accepted:
                    verified += 1
                reference = store.append_event(operation_id, {
                    "kind": "readback", "action_index": index,
                    "observed": observed, "confirmed": accepted})
                item["recovery_reference"]["readback"] = reference
                own = store.load(operation_id)
                verify_confirmation(plan, own, confirmation_hash)
                _originals(plan, own)
                if (own.journal_torn or not own.events
                        or own.events[-1]["hash"] != reference):
                    raise RecordError("restore readback durability/binding did not survive revalidation")
                if not accepted:
                    raise PlanError("restore command result/readback did not confirm exact state")
                item["confirmed"] = True
                completed.add(index)
            return ApplyResult("COMPLETED", operation_id, tuple(results), tuple(references))
    except errors as error:
        status = "PARTIAL" if verified else "FAILED" if attempted else "REFUSED"
        return ApplyResult("PARTIAL" if status == "FAILED" and verified else status,
                           operation_id, tuple(results), tuple(references), str(error))


def _apply_remove(plan, client, store, confirmation_hash):
    from .records import RecordError

    errors = (PlanError, RecordError, AdbError, DiscoveryError, PolicyError,
              OSError, ValueError, TypeError, KeyError)
    operation_id = plan.operation_id
    references = [str(store.root / operation_id / name)
                  for name in ("snapshot.json", "events.jsonl")]
    results = []
    attempted = False
    verified = 0
    try:
        if (len(plan.actions) != 1 or plan.actions[0]["type"] != "UNINSTALL_FRESH_APK"
                or client.android_user != plan.android_user):
            raise PlanError("APK removal requires one typed action and the exact Android user")
        action = plan.actions[0]
        package = action["package"]
        item = {
            "action_index": 0, "action": copy.deepcopy(action),
            "expected_before": {
                "package": package, "version_code": action["version_code"],
                "enabled_flag": plan.preconditions["remove_before_flags"][package]},
            "observed_before": None, "expected_after": {
                "package": None, "version_code": None, "enabled_flag": None},
            "observed_after": None, "confirmed": False, "attempted": False,
            "recovery_reference": {"operation_id": operation_id, "snapshot": references[0]}}
        results.append(item)
        with store.lock(plan.target):
            own = store.load(operation_id)
            verify_confirmation(plan, own, confirmation_hash)
            _originals(plan, own)
            source = store.load(plan.preconditions["source_operation_id"])
            if (source.plan.operation != "install"
                    or source.record_hash != plan.preconditions["source_record_hash"]
                    or list(source.event_references) != plan.preconditions["source_event_references"]
                    or source.journal_torn):
                raise PlanError("source install record changed or is not trusted")
            latest = _record_latest_events(source).get(0)
            if (latest is None or latest["event"]["kind"] != "readback"
                    or not latest["event"]["confirmed"]
                    or latest["event"]["observed"].get("package") != package
                    or latest["event"]["observed"].get("version_code") != action["version_code"]
                    or latest["event"]["observed"].get("system") is not False
                    or latest["event"]["observed"].get("global_present") is not True):
                raise PlanError("fresh non-system install has no matching durable evidence")
            own_events = [entry["event"] for entry in own.events
                          if entry["event"]["action_index"] == 0]
            latest_own = own_events[-1] if own_events else None
            completed = latest_own is not None and latest_own["kind"] == "readback" \
                and latest_own["confirmed"]
            report = discover(client)
            check_report(report, plan.target)
            if report.command_capabilities.get("uninstall_user") is not True:
                raise PlanError("per-user uninstall is no longer probed; use manual recovery")
            if completed:
                if package in report.packages:
                    raise PlanError("confirmed APK removal conflicts with current installation")
                item["observed_after"] = {"package": None, "version_code": None,
                                          "enabled_flag": None}
                item["confirmed"] = True
                return ApplyResult("COMPLETED", operation_id, tuple(results), tuple(references))
            if package not in report.packages:
                if latest_own is None:
                    raise PlanError("package was removed externally after its preview")
                if latest_own["kind"] == "intent":
                    verified += 1
                    reference = store.append_event(operation_id, {
                        "kind": "readback", "action_index": 0,
                        "observed": {"package": None, "version_code": None,
                                     "enabled_flag": None}, "confirmed": True})
                else:
                    store.append_event(operation_id, {"kind": "intent", "action_index": 0})
                    verified += 1
                    reference = store.append_event(operation_id, {
                        "kind": "readback", "action_index": 0,
                        "observed": {"package": None, "version_code": None,
                                     "enabled_flag": None}, "confirmed": True})
                item["recovery_reference"]["readback"] = reference
                own = store.load(operation_id)
                verify_confirmation(plan, own, confirmation_hash)
                _originals(plan, own)
                if own.journal_torn or own.events[-1]["hash"] != reference:
                    raise RecordError("APK removal readback durability/binding failed")
                item["observed_after"] = {"package": None, "version_code": None,
                                          "enabled_flag": None}
                item["confirmed"] = True
                return ApplyResult("COMPLETED", operation_id, tuple(results), tuple(references))
            if (report.packages.get(package) != plan.preconditions["remove_before_installed"][package]
                    or exact_flag(report, package) != plan.preconditions["remove_before_flags"][package]
                    or report.packages[package].get("system") is not False
                    or report.packages[package].get("version_code") != action["version_code"]
                    or "packages.%s.metadata" % package in report.unresolved_facts
                    or not _global_package_presence(client, package)):
                raise PlanError("installed package/flags conflict with separately confirmed removal")
            pending = bool(latest_own and latest_own["kind"] == "intent")
            if not pending:
                intent = store.append_event(operation_id, {"kind": "intent", "action_index": 0})
                item["recovery_reference"]["intent"] = intent
            own = store.load(operation_id)
            verify_confirmation(plan, own, confirmation_hash)
            _originals(plan, own)
            source = store.load(plan.preconditions["source_operation_id"])
            if (source.record_hash != plan.preconditions["source_record_hash"]
                    or list(source.event_references) != plan.preconditions["source_event_references"]
                    or source.journal_torn):
                raise PlanError("source install record changed after durable removal intent")
            report = discover(client)
            check_report(report, plan.target)
            if (report.packages.get(package) != plan.preconditions["remove_before_installed"][package]
                    or exact_flag(report, package) != plan.preconditions["remove_before_flags"][package]
                    or report.command_capabilities.get("uninstall_user") is not True
                    or not _global_package_presence(client, package)):
                raise PlanError("removal preconditions changed after durable intent")
            if client.identity() != plan.target or client.android_user != plan.android_user:
                raise PlanError("target/firmware/user changed immediately before removal")
            item["observed_before"] = {
                "package": package, "version_code": report.packages[package]["version_code"],
                "enabled_flag": exact_flag(report, package)}
            attempted = item["attempted"] = True
            command = client.shell(["pm", "uninstall", "--user",
                                    str(plan.android_user), package])
            after = discover(client)
            check_report(after, plan.target)
            if client.identity() != plan.target or client.android_user != plan.android_user:
                raise PlanError("target/firmware/user changed during removal readback")
            absent = package not in after.packages and package not in after.package_flags
            observed = {"package": None if absent else package,
                        "version_code": (None if absent else
                                         after.packages[package].get("version_code")),
                        "enabled_flag": exact_flag(after, package, allow_absent=True)}
            item["observed_after"] = observed
            accepted = (command.returncode == 0 and not command.stderr.strip()
                        and command.stdout.strip() == "Success" and absent)
            own = store.load(operation_id)
            verify_confirmation(plan, own, confirmation_hash)
            _originals(plan, own)
            source = store.load(plan.preconditions["source_operation_id"])
            if (source.record_hash != plan.preconditions["source_record_hash"]
                    or list(source.event_references) != plan.preconditions["source_event_references"]
                    or source.journal_torn):
                raise PlanError("source install record changed during APK removal")
            if accepted:
                verified += 1
            reference = store.append_event(operation_id, {
                "kind": "readback", "action_index": 0,
                "observed": observed, "confirmed": accepted})
            item["recovery_reference"]["readback"] = reference
            own = store.load(operation_id)
            verify_confirmation(plan, own, confirmation_hash)
            _originals(plan, own)
            if own.journal_torn or own.events[-1]["hash"] != reference:
                raise RecordError("APK removal readback durability/binding failed")
            if not accepted:
                raise PlanError("per-user APK removal result/readback is not confirmed")
            item["confirmed"] = True
        return ApplyResult("COMPLETED", operation_id, tuple(results), tuple(references))
    except errors as error:
        status = "PARTIAL" if verified else "FAILED" if attempted else "REFUSED"
        return ApplyResult(status, operation_id, tuple(results), tuple(references), str(error))


def _global_absence(client, action):
    # A package absent for this user can still be an update of another user's or hidden
    # system installation. Do not use per-user absence as signing/rollback compatibility.
    result = client.read(["dumpsys", "package", "packages"])
    headers = re.findall(r"^  Package \[([^\]]+)\] \(", result.stdout, re.M)
    if (result.returncode != 0 or result.stderr.strip()
            or "Packages:\n" not in result.stdout or not headers):
        raise PlanError("global package absence cannot be verified")
    if action["package"] in headers:
        raise PlanError("package exists globally; conflicting installs/updates are refused")


def _observed(action, report):
    if action["type"] == "SET_PACKAGE_ENABLED_STATE":
        return {"enabled_flag": exact_flag(report, action["package"])}
    package = action["package"]
    info = report.packages.get(package)
    return {"package": package if info is not None else None,
            "version_code": info.get("version_code") if info else None,
            "enabled_flag": exact_flag(report, package, allow_absent=True)}


def _global_package_presence(client, package):
    result = client.read(["dumpsys", "package", "packages"])
    headers = re.findall(r"^  Package \[([^\]]+)\] \(", result.stdout, re.M)
    if (result.returncode != 0 or result.stderr.strip()
            or "Packages:\n" not in result.stdout or not headers):
        raise PlanError("global package presence cannot be verified")
    return package in headers


def plan_remove_apk(record, current_report, client):
    """Preview removal separately; only a proven fresh non-system install is eligible."""
    try:
        record.plan.validate()
    except (AttributeError, ValueError, TypeError) as error:
        raise PlanError("missing or invalid source recovery record") from error
    check_report(current_report, record.target)
    if (current_report.home_candidates != record.original_home
            or current_report.unresolved_facts !=
            record.plan.preconditions.get("unresolved_facts")):
        raise PlanError("HOME or unresolved discovery facts changed since the source snapshot")
    if (record.plan.operation != "install" or len(record.plan.actions) != 1
            or record.journal_torn):
        raise PlanError("only a non-torn, fresh install record can preview APK removal")
    _record_latest_events(record)
    action = record.plan.actions[0]
    package = action["package"]
    if (action["type"] != "INSTALL_APK"
            or record.plan.preconditions.get("installed_packages", {}).get(package, object()) is not None
            or record.plan.preconditions.get("package_flags", {}).get(package, object()) is not None
            or record.installed_package_identities.get(package, object()) is not None
            or package in record.original_package_flags):
        raise PlanError("source record does not prove the package was absent before installation")
    installed = current_report.packages.get(package)
    flag = exact_flag(current_report, package, allow_absent=True)
    latest = _record_latest_events(record).get(0)
    observed = latest["event"]["observed"] if latest else {}
    if (latest is None or latest["event"]["kind"] != "readback"
            or not latest["event"]["confirmed"]
            or observed.get("package") != package
            or observed.get("version_code") != action["version_code"]
            or observed.get("system") is not False
            or observed.get("global_present") is not True
            or installed != {"system": False, "version_code": action["version_code"]}
            or flag not in (0, 1)
            or "packages.%s.metadata" % package in current_report.unresolved_facts):
        raise PlanError("fresh non-system APK installation identity is not durably established")
    if current_report.command_capabilities.get("uninstall_user") is not True:
        raise PlanError("per-user uninstall is not probed as supported; manual recovery: use "
                        "Android Settings for this exact user after reviewing the package")
    if not _global_package_presence(client, package):
        raise PlanError("installed APK is not present globally; no removal is needed")
    action = {"type": "UNINSTALL_FRESH_APK", "package": package,
              "version_code": action["version_code"]}
    result = _plan("remove", current_report, None, [action], limitations=(
        "Separately confirmed per-user removal only; not an application-data backup.",
        "Never removes an original, system, or other user's package."))
    preconditions = dict(result.preconditions)
    before_flags = dict(preconditions["package_flags"])
    before_installed = dict(preconditions["installed_packages"])
    preconditions.update({
        "package_flags": {package: None},
        "installed_packages": {package: None},
        "remove_before_flags": before_flags,
        "remove_before_installed": before_installed,
        "source_operation_id": record.operation_id,
        "source_record_hash": record.record_hash,
        "source_event_references": list(record.event_references),
    })
    return OperationPlan(**dict(result.__dict__, profile_hash=record.profile_hash,
                                profile_id=record.plan.profile_id,
                                artifact_hash=record.artifact_hash,
                                preconditions=preconditions))


def _accepted(action, command):
    if command.returncode != 0 or command.stderr.strip():
        return False
    if action["type"] == "INSTALL_APK":
        return tuple(command.stdout.strip().splitlines()) in {
            ("Success",), ("Performing Streamed Install", "Success"),
            ("Performing Push Install", "Success")}
    return re.fullmatch(r"(?:Package )?" + re.escape(action["package"])
                        + r" new state: disabled-user", command.stdout.strip()) is not None


def apply(plan, client, store, confirmation_hash):
    """Execute one confirmed batch under one record-root/target lock; never auto-replay."""
    from .records import RecordError  # Records consume typed plans; avoid an import cycle.

    errors = (PlanError, RecordError, AdbError, DiscoveryError, PolicyError,
              OSError, ValueError, TypeError, KeyError)
    attempted = False
    verified = 0
    results = []
    references = []
    operation_id = getattr(plan, "operation_id", "")
    try:
        # Detach mutable caller dictionaries. The saved plan, not later caller edits, is
        # authoritative; every subsequent read is through the integrity/durability API.
        if not isinstance(plan, OperationPlan):
            raise PlanError("apply requires a typed saved OperationPlan")
        plan = OperationPlan.from_dict(plan.to_dict())
        operation_id = plan.operation_id
        if plan.operation == "restore":
            return _apply_restore(plan, client, store, confirmation_hash)
        if plan.operation == "remove":
            return _apply_remove(plan, client, store, confirmation_hash)
        references = [str(store.root / operation_id / name)
                      for name in ("snapshot.json", "events.jsonl")]
        for index, action in enumerate(plan.actions):
            package_state = action["type"] == "SET_PACKAGE_ENABLED_STATE"
            expected_before = ({"enabled_flag": plan.preconditions["package_flags"][action["package"]]}
                               if package_state else
                               {"package": None, "version_code": None, "enabled_flag": None})
            expected_after = ({"enabled_flag": action["value"]} if package_state else
                              {"package": action.get("package"), "version_code": action.get("version_code")})
            results.append({"action_index": index, "action": copy.deepcopy(action),
                            "expected_before": expected_before, "observed_before": None,
                            "expected_after": expected_after, "observed_after": None,
                            "confirmed": False, "attempted": False,
                            "recovery_reference": {"operation_id": operation_id, "snapshot": references[0]}})
        if client.android_user != plan.android_user:
            raise PlanError("client Android user differs from the confirmed plan")
        with store.lock(plan.target):
            record = store.load(operation_id)
            verify_confirmation(plan, record, confirmation_hash)
            if record.events or record.journal_torn:
                raise PlanError("operation already has journal history; inspect recovery, do not replay")
            _originals(plan, record)
            completed = set()
            for index, action in enumerate(plan.actions):
                # Trust the original pair and proof afresh even after a successful action.
                record = store.load(operation_id)
                verify_confirmation(plan, record, confirmation_hash)
                _originals(plan, record)
                report = discover(client)
                check_report(report, plan.target)
                _qualification(plan, report)
                _fresh_state(plan, report, completed)
                if action["type"] == "INSTALL_APK":
                    _global_absence(client, action)
                item = results[index]
                item["observed_before"] = _observed(action, report)
                intent = store.append_event(operation_id, {"kind": "intent", "action_index": index})
                item["recovery_reference"]["intent"] = intent
                record = store.load(operation_id)
                verify_confirmation(plan, record, confirmation_hash)
                _originals(plan, record)
                if (record.journal_torn or record.events[-1]["hash"] != intent
                        or record.events[-1]["event"] != {"kind": "intent", "action_index": index}):
                    raise RecordError("durable intent does not bind this action")
                # Persistence itself may take time or fail after making content visible.
                # Re-read all gates after it, immediately before issuing the fixed command.
                report = discover(client)
                check_report(report, plan.target)
                _qualification(plan, report)
                _fresh_state(plan, report, completed)
                if action["type"] == "INSTALL_APK":
                    _global_absence(client, action)
                if client.android_user != plan.android_user or client.identity() != plan.target:
                    raise PlanError("target/firmware/user changed immediately before command")
                item["observed_before"] = _observed(action, report)
                attempted = item["attempted"] = True
                observed = {}
                failure = ""
                confirmed = False
                try:
                    if action["type"] == "INSTALL_APK":
                        command = client.install_apk(action["path"])
                    else:
                        command = client.shell(["pm", "disable-user", "--user",
                                                str(plan.android_user), action["package"]])
                    after = discover(client)
                    check_report(after, plan.target)
                    if client.android_user != plan.android_user or client.identity() != plan.target:
                        raise PlanError("target/firmware/user changed during readback")
                    observed = _observed(action, after)
                    if action["type"] == "INSTALL_APK":
                        observed["system"] = after.packages.get(action["package"], {}).get("system")
                        observed["global_present"] = _global_package_presence(
                            client, action["package"])
                        if observed["global_present"] is not True:
                            raise PlanError("freshly installed APK is absent from global readback")
                    _qualification(plan, after)
                    _fresh_state(plan, after, completed | {index})
                    if not _accepted(action, command):
                        raise PlanError("package manager result was refused, empty or unrecognized")
                    confirmed = True
                except errors as error:
                    failure = str(error)
                    observed["error"] = failure
                item["observed_after"] = observed
                # A verified observation without a durable result is partial, not completed;
                # the durable intent remains the recovery boundary if result persistence fails.
                if confirmed:
                    verified += 1
                reference = store.append_event(operation_id, {
                    "kind": "readback", "action_index": index,
                    "observed": observed, "confirmed": confirmed})
                item["recovery_reference"]["readback"] = reference
                record = store.load(operation_id)
                verify_confirmation(plan, record, confirmation_hash)
                if record.journal_torn or record.events[-1]["hash"] != reference:
                    raise RecordError("readback durability/binding did not survive revalidation")
                item["confirmed"] = confirmed
                if not confirmed:
                    raise PlanError(failure or "unexpected readback")
                completed.add(index)
        return ApplyResult("COMPLETED", operation_id, tuple(results), tuple(references))
    except errors as error:
        status = "PARTIAL" if verified else "FAILED" if attempted else "REFUSED"
        return ApplyResult(status, operation_id, tuple(results), tuple(references), str(error))


def _record_latest_events(record):
    if (record.operation_id != record.plan.operation_id or record.target != record.plan.target
            or len(record.events) != len(record.event_references)
            or tuple(entry.get("hash") for entry in record.events) != record.event_references):
        raise PlanError("source record/journal binding is invalid")
    latest = {}
    for entry in record.events:
        latest[entry["event"]["action_index"]] = entry
    return latest


def _validate_restore_source(plan, source):
    states = plan.preconditions["source_package_states"]
    expected_packages = {action["package"] for action in source.plan.actions
                         if "package" in action}
    if source.plan.operation == "restore" or set(states) != expected_packages:
        raise PlanError("restore source package state set does not match its saved operation")
    latest = _record_latest_events(source)
    for index, action in enumerate(source.plan.actions):
        if "package" not in action:
            continue
        package = action["package"]
        state = states[package]
        if (state["type"] != action["type"]
                or state["identity"] != source.installed_package_identities.get(package)
                or state["original_flag"] != source.original_package_flags.get(package)):
            raise PlanError("restore source package identity/original flag is not anchored")
        entry = latest.get(index)
        if action["type"] == "SET_PACKAGE_ENABLED_STATE":
            if (state["identity"] is None or state["current_identity"] != state["identity"]
                    or type(state["current_flag"]) is not int
                    or type(state["original_flag"]) is not int):
                raise PlanError("restore source package identity/flag is unresolved")
            if entry is None and state["current_flag"] != state["original_flag"]:
                raise PlanError("source state changed without a durable intent")
            if (entry and entry["event"]["kind"] == "readback"
                    and entry["event"]["confirmed"]
                    and state["current_flag"] not in (
                        entry["event"]["observed"].get("enabled_flag"),
                        state["original_flag"])):
                raise PlanError("restore source state differs from its durable readback/original")
        elif action["type"] == "INSTALL_APK":
            if state["identity"] is not None or state["original_flag"] is not None:
                raise PlanError("fresh install source does not preserve original absence")
            current_identity = state["current_identity"]
            if current_identity is None:
                if state["current_flag"] is not None:
                    raise PlanError("absent fresh APK has a fabricated enabled flag")
            elif (current_identity != {"system": False, "version_code": action["version_code"]}
                  or state["current_flag"] not in (0, 1)):
                raise PlanError("fresh APK before-state identity/flag is not exact")
            if (entry and entry["event"]["kind"] == "readback"
                    and entry["event"]["confirmed"]):
                observed = entry["event"]["observed"]
                if (current_identity is not None
                        and (observed.get("package") != package
                             or observed.get("version_code") != action["version_code"]
                             or observed.get("system") is not False
                             or observed.get("global_present") is not True)):
                    raise PlanError("fresh APK source facts differ from durable readback")
    return True


def plan_restore(record, current_report):
    """Create a separately confirmed inverse plan from anchored source history and readback."""
    try:
        record.plan.validate()
    except (AttributeError, ValueError, TypeError) as error:
        raise PlanError("missing or invalid source recovery record") from error
    check_report(current_report, record.target)
    latest = _record_latest_events(record)
    actions = []
    recoveries = []
    blockers = []
    limitations = []
    if record.journal_torn:
        message = ("Torn source journal tail leaves the remote outcome unconfirmed and "
                   "unresolved; manual recovery is required.")
        limitations.append(message)
        blockers.append(message)
    for index, action in enumerate(record.plan.actions):
        if action["type"] == "SET_PACKAGE_ENABLED_STATE":
            package = action["package"]
            original = record.original_package_flags.get(package)
            identity = record.installed_package_identities.get(package)
            if (type(original) is not int or identity is None
                    or current_report.packages.get(package) != identity
                    or "packages.%s.metadata" % package in current_report.unresolved_facts):
                raise PlanError("missing/mismatched installed identity or exact original flag: "
                                + package)
            current = exact_flag(current_report, package)
            observed = latest.get(index)
            if observed is None:
                if current != original:
                    raise PlanError("package changed without a durable write intent: " + package)
                continue
            event = observed["event"]
            if event["kind"] == "intent":
                if record.journal_torn:
                    continue
                if current not in (original, action["value"]):
                    raise PlanError("pending write readback conflicts with current state: " + package)
                recoveries.append({"action_index": index,
                                   "observed": {"enabled_flag": current},
                                   "confirmed": current == action["value"],
                                   "intent_reference": observed["hash"]})
                if current == action["value"] and current != original:
                    _state_restore_command(current_report, original)
                    actions.append({"type": "SET_PACKAGE_ENABLED_STATE",
                                    "package": package, "value": original})
                continue
            if event["confirmed"]:
                applied = event["observed"].get("enabled_flag")
                if current == original:
                    continue
                if current != applied:
                    raise PlanError("independent package state change conflicts with recorded "
                                    "readback: " + package)
                _state_restore_command(current_report, original)
                actions.append({"type": "SET_PACKAGE_ENABLED_STATE",
                                "package": package, "value": original})
                continue
            if current not in (original, action["value"]):
                raise PlanError("failed operation readback conflicts with current package state: "
                                + package)
            limitations.append("Failed/unconfirmed source readback for action %d; fresh state "
                               "must be journaled before recovery." % index)
            recoveries.append({"action_index": index,
                               "observed": {"enabled_flag": current},
                               "confirmed": current == action["value"],
                               "intent_reference": observed["hash"]})
            if current == action["value"] and current != original:
                _state_restore_command(current_report, original)
                actions.append({"type": "SET_PACKAGE_ENABLED_STATE",
                                "package": package, "value": original})
        elif action["type"] == "INSTALL_APK":
            package = action["package"]
            latest_entry = latest.get(index)
            if latest_entry is not None and latest_entry["event"]["confirmed"]:
                info = current_report.packages.get(package)
                flag = exact_flag(current_report, package, allow_absent=True)
                observed = latest_entry["event"]["observed"]
                if info is not None and (
                        info.get("version_code") != action["version_code"]
                        or info.get("system") is not False
                        or flag not in (0, 1)
                        or observed.get("package") != package
                        or observed.get("version_code") != action["version_code"]):
                    raise PlanError("installed APK identity/flags conflict with its journal")
                if info is not None:
                    message = ("Fresh APK removal requires a separate plan and confirmation; "
                               "no application-data rollback is implied.")
                    limitations.append(message)
                    blockers.append(message)
            else:
                message = ("APK installation outcome is not durably confirmed; inspect manually "
                           "before creating a separate removal preview.")
                limitations.append(message)
                blockers.append(message)
        elif action["type"] == "SET_SETTING":
            message = "Settings restore remains unsupported without a qualified, probed inverse."
            limitations.append(message)
            blockers.append(message)
    if not actions:
        limitations.append(
            "No confirmed operation changes need a package-state inverse; original values alone "
            "do not prove a write.")
        if not recoveries and not any(
                entry["event"]["kind"] == "readback" and entry["event"]["confirmed"]
                for entry in record.events):
            message = "No durable source write is confirmed; this is not an executable restore."
            limitations.append(message)
            blockers.append(message)
    source_package_states = {}
    for action in record.plan.actions:
        if "package" not in action:
            continue
        package = action["package"]
        source_package_states[package] = {
            "type": action["type"],
            "identity": copy.deepcopy(record.installed_package_identities.get(package)),
            "current_identity": copy.deepcopy(current_report.packages.get(package)),
            "current_flag": exact_flag(
                current_report, package, allow_absent=action["type"] == "INSTALL_APK"),
            "original_flag": record.original_package_flags.get(package),
        }
    result = _plan("restore", current_report, None, actions, limitations=limitations)
    preconditions = dict(result.preconditions)
    preconditions["restore_before_flags"] = dict(preconditions["package_flags"])
    preconditions["restore_blockers"] = blockers
    preconditions["package_flags"] = {
        action["package"]: record.original_package_flags[action["package"]]
        for action in actions}
    return OperationPlan(**dict(result.__dict__, profile_hash=record.profile_hash,
                                profile_id=record.plan.profile_id,
                                preconditions=dict(preconditions,
                                                   source_operation_id=record.operation_id,
                                                   source_record_hash=record.record_hash,
                                                   source_event_references=list(record.event_references),
                                                   source_recoveries=recoveries,
                                                   source_package_states=source_package_states)))
