"""Private, durable originals and append-only intent/readback records (macOS/Linux)."""
import copy
import fcntl
import json
import os
import stat
import threading
import uuid
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path

from .adb import TargetIdentity
from .operations import (OperationPlan, PlanError, _ID, canonical, check_report,
                         content_hash, exact_flag, validate_target)


class RecordError(RuntimeError):
    """Persistence, target binding or exact original state cannot be guaranteed."""


def _sync_directory(path):
    fd = os.open(str(path), os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def _private_directory(path):
    for ancestor in (path,) + tuple(path.parents):
        if ancestor.is_symlink():
            # macOS /var is a root-owned OS alias. Never follow user-controlled aliases.
            if ancestor == path or ancestor.lstat().st_uid != 0:
                raise RecordError("user-controlled symlink in private record path: %s" % ancestor)
    if not path.exists() and not path.is_symlink():
        if not path.parent.exists():
            _private_directory(path.parent)
        path.mkdir(mode=0o700)
        _sync_directory(path.parent)
    info = path.lstat()
    if (not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o700):
        raise RecordError("record directory must be owned, private (0700), and not a symlink: %s" % path)


def _open_private(path, flags):
    fd = os.open(str(path), flags | os.O_NOFOLLOW, 0o600)
    info = os.fstat(fd)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
        os.close(fd)
        raise RecordError("record file must be owned, private (0600), and not linked")
    return fd


def _read_bytes(path):
    fd = _open_private(path, os.O_RDONLY)
    with os.fdopen(fd, "rb") as handle:
        return handle.read()


def _decode_json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result

    def reject_constant(value):
        raise ValueError("non-finite JSON value: " + value)

    return json.loads(data, object_pairs_hook=pairs, parse_constant=reject_constant)


def _read_json(path):
    try:
        return _decode_json(_read_bytes(path))
    except (OSError, ValueError) as error:
        raise RecordError("cannot read valid private snapshot: %s" % error) from error


def write_private_json(path, data):
    """Atomic same-directory replacement, with both file and directory durability."""
    path = Path(path)
    temp = path.with_name(".%s.%s.tmp" % (path.name, uuid.uuid4().hex))
    try:
        _private_directory(path.parent)
        if path.exists() or path.is_symlink():
            fd = _open_private(path, os.O_RDONLY)
            os.close(fd)
        fd = _open_private(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
        with os.fdopen(fd, "wb") as handle:
            handle.write(canonical(data) + b"\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(str(temp), str(path))
        _sync_directory(path.parent)
    except (OSError, ValueError, TypeError) as error:
        raise RecordError("durable snapshot failed: %s" % error) from error
    finally:
        if temp.exists():
            temp.unlink()


def _read_anchored(path):
    data = _read_json(path)
    anchor = _read_json(path.with_suffix(".integrity.json"))
    if (not isinstance(anchor, dict) or set(anchor) != {"schema_version", "sha256"}
            or type(anchor["schema_version"]) is not int or anchor["schema_version"] != 1
            or anchor["sha256"] != content_hash(data)):
        raise RecordError("saved content does not match its durable integrity anchor: %s" % path)
    # A matching pair may be visible after its final directory fsync failed. Establish
    # durability afresh on every read; never use matching hashes as a durability receipt.
    try:
        for saved_path in (path, path.with_suffix(".integrity.json")):
            fd = _open_private(saved_path, os.O_RDONLY)
            try:
                os.fsync(fd)
            finally:
                os.close(fd)
        _sync_directory(path.parent)
    except OSError as error:
        raise RecordError("anchored content durability revalidation failed: %s" % error) from error
    return data


def _write_anchored(path, data, immutable=False):
    anchor_path = path.with_suffix(".integrity.json")
    exists = path.exists() or path.is_symlink() or anchor_path.exists() or anchor_path.is_symlink()
    previous = _read_anchored(path) if exists else None
    if immutable and exists and previous != data:
        raise RecordError("immutable snapshot cannot replace previously anchored originals")
    # This is deliberately a fail-closed two-file commit, not a claimed atomic transaction.
    # If interrupted between replacements the mismatch remains visible and cannot be repaired
    # from new device observations. Both files individually retain complete durable JSON.
    write_private_json(path, data)
    if not exists or previous != data:
        write_private_json(anchor_path, {"schema_version": 1, "sha256": content_hash(data)})


@dataclass(frozen=True)
class RecoveryRecord:
    operation_id: str
    target: TargetIdentity
    original_package_flags: dict
    original_settings: dict
    original_home: list
    installed_package_identities: dict
    operation_hash: str
    profile_hash: str
    list_hash: str
    artifact_hash: str
    plan: OperationPlan
    events: tuple = ()
    event_references: tuple = ()
    journal_torn: bool = False
    schema_version: int = 1

    @property
    def android_user(self):
        return self.target.android_user

    @property
    def firmware_hash(self):
        return self.target.firmware_hash

    def snapshot_dict(self):
        return {"schema_version": self.schema_version, "operation_id": self.operation_id,
                "target": self.plan.to_dict()["target"],
                "original_package_flags": copy.deepcopy(self.original_package_flags),
                "original_settings": copy.deepcopy(self.original_settings),
                "original_home": copy.deepcopy(self.original_home),
                "installed_package_identities": copy.deepcopy(self.installed_package_identities),
                "operation_hash": self.operation_hash, "profile_hash": self.profile_hash,
                "list_hash": self.list_hash, "artifact_hash": self.artifact_hash,
                "plan": self.plan.to_dict()}

    def to_dict(self):
        return dict(self.snapshot_dict(), events=list(self.events),
                    event_references=list(self.event_references), journal_torn=self.journal_torn)

    @property
    def record_hash(self):
        return content_hash(self.snapshot_dict())

    def export_summary(self):
        """Allowlisted non-identifying summary; private packages/settings never leave by default."""
        return {"schema_version": 1, "operation": self.plan.operation,
                "action_count": len(self.plan.actions), "event_count": len(self.events),
                "journal_torn": self.journal_torn,
                "limitations": ["Not an application-data or firmware backup.",
                                "Review any additional data before sharing."]}


class RecordStore:
    def __init__(self, root=None):
        self.root = Path(root) if root is not None else Path.home() / ".cleanyourcar" / "records"
        self._held = {}

    def _directory(self, operation_id):
        if not isinstance(operation_id, str) or not _ID.fullmatch(operation_id):
            raise RecordError("invalid operation ID (no paths permitted)")
        return self.root / operation_id

    @contextmanager
    def lock(self, target):
        """Nonblocking exclusive target lock, covering all firmware/users and entire batches."""
        try:
            validate_target(target)
            _private_directory(self.root)
            locks = self.root / ".locks"
            _private_directory(locks)
            key = target.binding_hash
            owner = (os.getpid(), threading.get_ident())
            if key in self._held:
                if self._held[key][0] != owner:
                    raise RecordError("another thread holds this target lock")
                yield
                return
            if self._held:
                raise RecordError("another target/identity alias is already locked by this store")
            # Discovery's frozen binding can include a user-scoped Android ID. Until a
            # physical-only binding is available, serialize this root as well as the target.
            guard = _open_private(locks / "store.lock", os.O_RDWR | os.O_CREAT)
            try:
                fcntl.flock(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError as error:
                os.close(guard)
                raise RecordError("concurrent operation or identity alias refused") from error
            try:
                with self._target_lock(locks, key, owner):
                    yield
            finally:
                fcntl.flock(guard, fcntl.LOCK_UN)
                os.close(guard)
        except (OSError, PlanError) as error:
            raise RecordError("target lock/durability failed: %s" % error) from error

    @contextmanager
    def _target_lock(self, locks, key, owner):
        fd = _open_private(locks / (key + ".lock"), os.O_RDWR | os.O_CREAT)
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise RecordError("concurrent operation refused for this target") from error
            _sync_directory(locks)
            self._held[key] = (owner, fd)
            try:
                yield
            finally:
                del self._held[key]
                fcntl.flock(fd, fcntl.LOCK_UN)
        finally:
            os.close(fd)

    def save_original(self, plan, state):
        """Save earliest facts across operation IDs, retries and extensions on this target/user."""
        try:
            plan.validate()
            report = state["report"] if isinstance(state, dict) else state
            settings = state.get("settings", {}) if isinstance(state, dict) else {}
            check_report(report, plan.target)
            with self.lock(plan.target):
                return self._save(plan, report, settings)
        except (OSError, ValueError, TypeError, KeyError) as error:
            raise RecordError("original snapshot refused: %s" % error) from error

    def _save(self, plan, report, settings):
        directory = self._directory(plan.operation_id)
        _private_directory(directory)
        originals_dir = self.root / ".originals"
        _private_directory(originals_dir)
        original_path = originals_dir / ("%s-user-%d.json" %
                                         (plan.target.binding_hash, plan.android_user))
        aggregate = {"target": plan.to_dict()["target"], "package_flags": {}, "settings": {},
                     "home": copy.deepcopy(report.home_candidates), "installed_packages": {}}
        if original_path.exists() or original_path.with_suffix(".integrity.json").exists():
            aggregate = _read_anchored(original_path)
            if aggregate.get("target") != plan.to_dict()["target"]:
                raise RecordError("earliest originals belong to a different firmware; fresh recovery required")
        # Every previously saved record is independent original truth. Never reconstruct a
        # missing aggregate or accept edits (even with a replaced aggregate anchor) that
        # conflict with an existing snapshot for this target/user.
        for snapshot_path in self.root.glob("*/snapshot.json"):
            saved = _read_anchored(snapshot_path)
            target = saved.get("target", {})
            if (target.get("binding_hash") == plan.target.binding_hash
                    and target.get("android_user") == plan.android_user):
                self._load(snapshot_path.parent.name)
                self._check_originals(saved, aggregate)
        packages = []
        setting_keys = []
        for action in plan.actions:
            if "package" in action:
                package = action["package"]
                packages.append(package)
                flag = exact_flag(report, package, allow_absent=action["type"] == "INSTALL_APK")
                aggregate["installed_packages"].setdefault(package, copy.deepcopy(report.packages.get(package)))
                if flag is not None and aggregate["installed_packages"][package] is not None:
                    aggregate["package_flags"].setdefault(package, flag)
                if (action["type"] == "SET_PACKAGE_ENABLED_STATE"
                        and package not in aggregate["package_flags"]):
                    raise RecordError("package was originally absent; no original enabled flag exists")
            else:
                key = action["namespace"] + "/" + action["name"]
                setting_keys.append(key)
                if (key not in settings or (settings[key] is not None and not isinstance(settings[key], str))
                        or "settings." + key in report.unresolved_facts):
                    raise RecordError("missing exact original setting: " + key)
                aggregate["settings"].setdefault(key, settings[key])
        if {"home.default", "home.candidates"} & set(report.unresolved_facts):
            raise RecordError("original HOME ownership is unresolved")
        record = RecoveryRecord(
            operation_id=plan.operation_id, target=plan.target,
            original_package_flags={p: aggregate["package_flags"][p] for p in packages
                                    if p in aggregate["package_flags"]},
            original_settings={k: aggregate["settings"][k] for k in setting_keys},
            original_home=copy.deepcopy(aggregate["home"]),
            installed_package_identities={p: aggregate["installed_packages"][p] for p in packages},
            operation_hash=plan.plan_hash, profile_hash=plan.profile_hash,
            list_hash=plan.list_hash, artifact_hash=plan.artifact_hash, plan=plan)
        snapshot = directory / "snapshot.json"
        if snapshot.exists():
            previous = self._load(plan.operation_id)
            if previous.operation_hash != record.operation_hash:
                raise RecordError("operation ID already binds a different plan; create a new preview")
            record = previous
        # Persist the shared earliest originals first. An orphaned aggregate is safe: it cannot
        # authorize a command, but prevents a later retry from replacing the first observation.
        _write_anchored(original_path, aggregate)
        _write_anchored(snapshot, record.snapshot_dict(), immutable=True)
        return self._load(plan.operation_id)

    def load(self, operation_id):
        try:
            record = self._load(operation_id)
            with self.lock(record.target):
                return self._load(operation_id)
        except (OSError, ValueError, TypeError, KeyError) as error:
            raise RecordError("record load refused: %s" % error) from error

    def _load(self, operation_id):
        directory = self._directory(operation_id)
        _private_directory(self.root)
        _private_directory(directory)
        data = _read_anchored(directory / "snapshot.json")
        plan = OperationPlan.from_dict(data["plan"])
        values = dict(data, target=TargetIdentity(**data["target"]), plan=plan)
        record = RecoveryRecord(**values)
        if (record.schema_version != 1 or record.operation_id != operation_id
                or record.target != plan.target or record.operation_hash != plan.plan_hash
                or record.profile_hash != plan.profile_hash or record.list_hash != plan.list_hash
                or record.artifact_hash != plan.artifact_hash):
            raise RecordError("snapshot binding/hash mismatch")
        aggregate_path = self.root / ".originals" / ("%s-user-%d.json" %
                                                   (record.target.binding_hash, record.android_user))
        _private_directory(aggregate_path.parent)
        self._check_originals(data, _read_anchored(aggregate_path))
        for action in plan.actions:
            if action["type"] == "SET_PACKAGE_ENABLED_STATE":
                value = record.original_package_flags.get(action["package"])
                if type(value) is not int or value not in range(5):
                    raise RecordError("snapshot lacks exact original package flag")
        events, torn, _ = self._journal(directory, plan)
        return RecoveryRecord(**dict(values, events=tuple(events),
                                     event_references=tuple(e["hash"] for e in events),
                                     journal_torn=bool(torn)))

    def _check_originals(self, snapshot, aggregate):
        if snapshot["target"] != aggregate.get("target"):
            raise RecordError("snapshot and earliest-original aggregate targets differ")
        if snapshot["original_home"] != aggregate.get("home"):
            raise RecordError("snapshot and aggregate disagree on original HOME")
        plan = OperationPlan.from_dict(snapshot["plan"])
        expected = {"original_package_flags": set(), "original_settings": set(),
                    "installed_package_identities": set()}
        for action in plan.actions:
            if action["type"] == "SET_SETTING":
                expected["original_settings"].add(action["namespace"] + "/" + action["name"])
            else:
                package = action["package"]
                expected["installed_package_identities"].add(package)
                identities = aggregate.get("installed_packages")
                if not isinstance(identities, dict) or package not in identities:
                    raise RecordError("aggregate lacks required original installation identity: " + package)
                if action["type"] == "SET_PACKAGE_ENABLED_STATE" or identities[package] is not None:
                    expected["original_package_flags"].add(package)
        for snapshot_key, aggregate_key in (
                ("original_package_flags", "package_flags"),
                ("original_settings", "settings"),
                ("installed_package_identities", "installed_packages")):
            original = aggregate.get(aggregate_key)
            if not isinstance(original, dict):
                raise RecordError("invalid earliest-original aggregate field: " + aggregate_key)
            saved = snapshot.get(snapshot_key)
            if not isinstance(saved, dict) or set(saved) != expected[snapshot_key]:
                raise RecordError("snapshot lacks complete typed-action originals: " + snapshot_key)
            for key, value in saved.items():
                if key not in original or canonical(value) != canonical(original[key]):
                    raise RecordError("snapshot and aggregate disagree on original " + key)

    def _validate_event(self, plan, event, events):
        if not isinstance(event, dict):
            raise RecordError("journal event must be an object")
        kind = event.get("kind")
        fields = ({"kind", "action_index"} if kind == "intent"
                  else {"kind", "action_index", "observed", "confirmed"})
        index = event.get("action_index")
        if (kind not in {"intent", "readback"} or set(event) != fields
                or type(index) is not int or not 0 <= index < len(plan.actions)):
            raise RecordError("invalid journal event type/action reference")
        previous = [e["event"] for e in events if e["event"]["action_index"] == index]
        if kind == "intent":
            if previous and previous[-1]["kind"] == "intent":
                raise RecordError("pending intent requires readback, not another command")
            return
        if not previous or previous[-1]["kind"] != "intent":
            raise RecordError("readback requires a preceding unmatched durable intent")
        if type(event["confirmed"]) is not bool or not isinstance(event["observed"], dict):
            raise RecordError("readback must explicitly declare observed state and confirmation")
        action = plan.actions[index]
        observed = event["observed"]
        if action["type"] == "SET_PACKAGE_ENABLED_STATE":
            flag = observed.get("enabled_flag")
            if event["confirmed"] and (type(flag) is not int or flag != action["value"]):
                raise RecordError("readback is not the requested exact enabled state")
        elif action["type"] == "INSTALL_APK":
            if event["confirmed"] and (observed.get("package") != action["package"]
                                       or observed.get("version_code") != action["version_code"]):
                raise RecordError("installed identity readback does not match the APK")
        elif event["confirmed"] and observed.get("value", object()) != action["value"]:
            raise RecordError("setting readback does not match the requested state")

    def _journal(self, directory, plan):
        path = directory / "events.jsonl"
        if not path.exists() and not path.is_symlink():
            return [], b"", 0
        data = _read_bytes(path)
        lines = data.splitlines(keepends=True)
        events = []
        valid_bytes = 0
        previous = ""
        for position, line in enumerate(lines):
            if not line.endswith(b"\n"):
                if position == len(lines) - 1:
                    return events, line, valid_bytes
                raise RecordError("journal has an invalid interior line")
            try:
                entry = _decode_json(line)
                payload = {k: entry[k] for k in ("sequence", "previous_hash", "event")}
                if (set(entry) != {"sequence", "previous_hash", "event", "hash"}
                        or entry["sequence"] != len(events) or entry["previous_hash"] != previous
                        or entry["hash"] != content_hash(payload)):
                    raise RecordError("journal sequence/hash chain is corrupt")
                self._validate_event(plan, entry["event"], events)
            except (ValueError, TypeError, KeyError) as error:
                raise RecordError("complete journal entry is corrupt: %s" % error) from error
            events.append(entry)
            previous = entry["hash"]
            valid_bytes += len(line)
        return events, b"", valid_bytes

    def append_event(self, operation_id, event):
        try:
            record = self.load(operation_id)
            with self.lock(record.target):
                record = self._load(operation_id)
                directory = self._directory(operation_id)
                events, torn, valid_bytes = self._journal(directory, record.plan)
                self._validate_event(record.plan, event, events)
                journal = directory / "events.jsonl"
                if torn:
                    backup = _open_private(directory / "events.torn", os.O_WRONLY | os.O_CREAT | os.O_APPEND)
                    with os.fdopen(backup, "ab") as handle:
                        handle.write(torn + b"\n")
                        handle.flush()
                        os.fsync(handle.fileno())
                    _sync_directory(directory)
                    fd = _open_private(journal, os.O_WRONLY)
                    try:
                        os.ftruncate(fd, valid_bytes)
                        os.fsync(fd)
                    finally:
                        os.close(fd)
                payload = {"sequence": len(events), "previous_hash": events[-1]["hash"] if events else "",
                           "event": copy.deepcopy(event)}
                entry = dict(payload, hash=content_hash(payload))
                fd = _open_private(journal, os.O_WRONLY | os.O_CREAT | os.O_APPEND)
                with os.fdopen(fd, "ab") as handle:
                    handle.write(canonical(entry) + b"\n")
                    handle.flush()
                    os.fsync(handle.fileno())
                _sync_directory(directory)
                return entry["hash"]
        except (OSError, ValueError, TypeError, KeyError) as error:
            raise RecordError("durable journal append refused: %s" % error) from error
