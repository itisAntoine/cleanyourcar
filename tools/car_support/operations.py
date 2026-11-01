"""Typed, target-bound previews only. Remote execution belongs to the executor."""
import copy
import hashlib
import hmac
import json
import re
import uuid
from dataclasses import asdict, dataclass

from .adb import TargetIdentity, environment_key
from .profiles import (PolicyError, candidate_list_hash, profile_hash,
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
            "Preview only: no remote executor is supplied by Task 3.",
            "Records are not firmware or application-data backups.",
            "HOME and permission changes require separate on-device approval.",))


def plan_debloat(report, profile, policy, candidates):
    validate_debloat_policy(report, profile, policy, candidates)
    check_report(report)
    for package in candidates:
        exact_flag(report, package)
    actions = [{"type": "SET_PACKAGE_ENABLED_STATE", "package": p, "value": 3}
               for p in candidates]
    return _plan("debloat", report, profile, actions, candidate_list_hash(candidates),
                 limitations=("Unresolved facts: " + ", ".join(report.unresolved_facts),))


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
    return _plan("install", report, profile, [action], artifact_hash=action["sha256"],
                 limitations=("APK metadata and signing compatibility require executor verification.",
                              "New APK removal needs a separate preview/confirmation (Task 5).",
                              "Optional companion is a separate qualified installation.",
                              "Unresolved facts: " + ", ".join(report.unresolved_facts)))


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
