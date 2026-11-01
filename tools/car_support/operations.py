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
                or self.operation not in {"debloat", "install", "restore"}):
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
            "Apply requires a separately confirmed saved plan; restoration belongs to Task 5.",
            "Records are not firmware or application-data backups.",
            "HOME and permission changes require separate on-device approval.",))


def plan_debloat(report, profile, policy, candidates):
    candidates = validate_candidates(candidates)
    validate_debloat_policy(report, profile, policy, candidates)
    check_report(report)
    for package in candidates:
        exact_flag(report, package)
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
                              "New APK removal needs a separate preview/confirmation (Task 5).",
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


@dataclass(frozen=True)
class ApplyResult:
    status: str
    operation_id: str
    actions: tuple
    recovery_references: tuple
    error: str = ""
    recovery_instructions: tuple = (
        "Stop; inspect the private snapshot and intent/readback journal on the same target/user.",
        "Do not replay pending or failed actions or run legacy scripts as a fallback.",
        "Full restoration and separately confirmed new-APK removal belong to Task 5.",
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
    if (set(before) != {"package_flags", "installed_packages", "home_candidates",
                       "unresolved_facts", "write_qualification"}
            or set(before["package_flags"]) != packages
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


def plan_restore(record, current_report):
    check_report(current_report, record.target)
    for action in record.plan.actions:
        if "package" in action:
            package = action["package"]
            exact_flag(current_report, package, allow_absent=action["type"] == "INSTALL_APK")
            if action["type"] == "SET_PACKAGE_ENABLED_STATE":
                previous = record.plan.preconditions.get("installed_packages", {}).get(package)
                if (previous is None or current_report.packages.get(package) != previous
                        or "packages.%s.metadata" % package in current_report.unresolved_facts):
                    raise PlanError("installed package identity changed or is unresolved: " + package)
    actions = []
    limitations = ["Full state restoration and APK removal are not implemented (Task 5)."]
    if record.journal_torn:
        limitations.append("Torn journal tail: unconfirmed command outcome remains unknown.")
    pending = set()
    confirmed = {}
    for entry in record.events:
        event = entry["event"]
        index = event["action_index"]
        if event["kind"] == "intent":
            pending.add(index)
        else:
            pending.discard(index)
            if event["confirmed"]:
                confirmed[index] = event["observed"]
            else:
                confirmed.pop(index, None)
                limitations.append("Failed/unconfirmed readback for action %d: %s" %
                                   (index, json.dumps(event["observed"], sort_keys=True)))
    if pending:
        limitations.append("Pending intent: unconfirmed remote outcome; manual inspection required.")
    for index, observed in sorted(confirmed.items()):
        action = record.plan.actions[index]
        if index in pending:
            continue
        if action["type"] == "SET_PACKAGE_ENABLED_STATE":
            package = action["package"]
            original = record.original_package_flags.get(package)
            if type(original) is not int:
                raise PlanError("missing exact original flag for " + package)
            current = exact_flag(current_report, package)
            if current == original:
                continue
            if current != observed["enabled_flag"]:
                raise PlanError("independent package change conflicts with recorded readback: " + package)
            actions.append({"type": "SET_PACKAGE_ENABLED_STATE", "package": package, "value": original})
        else:
            limitations.append("No automatic %s restoration with currently available facts." % action["type"])
    if not confirmed:
        limitations.append("No confirmed operation changes; originals alone do not prove a write occurred.")
    result = _plan("restore", current_report, None, actions, limitations=limitations)
    return OperationPlan(**dict(result.__dict__, profile_hash=record.profile_hash,
                                profile_id=record.plan.profile_id,
                                preconditions=dict(result.preconditions,
                                                   source_operation_id=record.operation_id,
                                                   source_record_hash=record.record_hash,
                                                   source_event_references=list(record.event_references))))
