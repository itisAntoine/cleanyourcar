"""Offline safety contracts for durable records and read-only plan previews."""
import copy
import errno
import importlib.util
import io
import json
import os
import stat
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from test_profiles import NoProcessTestCase, qualified_case

try:
    from car_support.records import RecordError, RecordStore
    from car_support.operations import (OperationPlan, PlanError, plan_debloat,
                                        plan_install, plan_restore, verify_confirmation)
except ImportError:
    RecordStore = None


class RecordsTest(NoProcessTestCase):
    def setUp(self):
        super().setUp()
        self.assertIsNotNone(RecordStore, "Task 3 records/planners are not implemented")
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name) / "records"
        self.store = RecordStore(self.root)
        self.report, self.profile, self.policy, self.candidates, self.fake = qualified_case()
        self.plan = plan_debloat(self.report, self.profile, self.policy, self.candidates)

    def save(self, plan=None, report=None):
        return self.store.save_original(plan or self.plan, report or self.report)

    def test_snapshot_failure_means_zero_writes(self):
        with mock.patch("car_support.records.os.fsync",
                        side_effect=OSError(errno.ENOSPC, "disk full")):
            with self.assertRaises(RecordError):
                self.save()
        self.assertEqual(self.fake.mutating_calls, [])
        with self.assertRaises(RecordError):
            self.store.load(self.plan.operation_id)

    def test_retry_keeps_first_original_state(self):
        first = self.save()
        changed = copy.deepcopy(self.report)
        changed.package_flags[self.candidates[0]] = 1
        changed.home_candidates[0]["default"] = True
        changed.home_candidates[1]["default"] = False
        retry = replace(self.plan, operation_id="retry-operation")
        retried = self.save(retry, changed)
        self.assertEqual(retried.original_package_flags, first.original_package_flags)
        self.assertEqual(retried.original_home, first.original_home)
        self.assertEqual(retried.installed_package_identities, first.installed_package_identities)

    def test_extended_plan_only_adds_new_originals(self):
        first = self.save()
        changed = copy.deepcopy(self.report)
        changed.package_flags[self.candidates[0]] = 1
        changed.packages["com.example.extra"] = copy.deepcopy(changed.packages[self.candidates[0]])
        changed.package_flags["com.example.extra"] = 0
        extended = replace(self.plan, operation_id="extended-operation",
                           actions=self.plan.actions + (
                               {"type": "SET_PACKAGE_ENABLED_STATE",
                                "package": "com.example.extra", "value": 3},))
        record = self.save(extended, changed)
        self.assertEqual(record.original_package_flags[self.candidates[0]],
                         first.original_package_flags[self.candidates[0]])
        self.assertEqual(record.original_package_flags["com.example.extra"], 0)
        self.assertNotIn("com.example.extra", first.original_package_flags)

    def test_atomic_replace_survives_interrupted_save(self):
        first = self.save()
        with mock.patch("car_support.records.os.replace",
                        side_effect=OSError(errno.ENOSPC, "interrupted replace")):
            with self.assertRaises(RecordError):
                self.save()
        self.assertEqual(self.store.load(self.plan.operation_id).to_dict(), first.to_dict())

    def test_concurrent_operation_refused(self):
        other = RecordStore(self.root)
        with self.store.lock(self.plan.target):
            with self.assertRaises(RecordError):
                other.save_original(self.plan, self.report)
            different_user = replace(self.plan.target, android_user=10)
            with self.assertRaises(RecordError):
                with other.lock(different_user):
                    self.fail("lock must cover the entire target, not just one user")
            # Frozen discovery binding can change with Android user/identity source.
            alias = replace(different_user, binding_hash="c" * 64)
            with self.assertRaises(RecordError):
                with other.lock(alias):
                    self.fail("an identity alias must not bypass exclusive operation locking")
        self.save()

    def test_modified_plan_or_confirmation_refused(self):
        record = self.save()
        confirmation = self.plan.confirmation_hash(record)
        verify_confirmation(self.plan, record, confirmation)
        modified = replace(self.plan, actions=({"type": "SET_PACKAGE_ENABLED_STATE",
                                               "package": self.candidates[0], "value": 1},))
        for plan, token in ((modified, confirmation), (self.plan, "0" * 64)):
            with self.subTest(plan=plan), self.assertRaises(PlanError):
                verify_confirmation(plan, record, token)
        self.assertEqual(self.fake.mutating_calls, [])

    def test_boolean_schema_version_and_shell_actions_refused(self):
        for modified in (replace(self.plan, schema_version=True),
                         replace(self.plan, actions=({"type": "SHELL", "command": "pm enable x"},))):
            with self.subTest(modified=modified), self.assertRaises(PlanError):
                OperationPlan.from_dict(modified.to_dict())

    def test_new_target_cannot_reuse_old_record(self):
        record = self.save()
        for field, value in (("binding_hash", "a" * 64),
                             ("firmware_hash", "b" * 64), ("android_user", 10)):
            changed = copy.deepcopy(self.report)
            changed.identity = replace(changed.identity, **{field: value})
            if field == "android_user":
                changed.android_user = value
            with self.subTest(field=field), self.assertRaises((PlanError, RecordError)):
                plan_restore(record, changed)
        changed_plan = replace(self.plan, target=replace(self.plan.target, firmware_hash="b" * 64))
        with self.assertRaises(RecordError):
            self.save(changed_plan)

    def test_missing_exact_flags_refused_even_if_original_exists(self):
        self.save()
        for mode in ("missing", "unresolved", "invalid"):
            changed = copy.deepcopy(self.report)
            if mode == "missing":
                del changed.package_flags[self.candidates[0]]
            elif mode == "unresolved":
                changed.unresolved_facts.append("package_flags." + self.candidates[0])
            else:
                changed.package_flags[self.candidates[0]] = True
            with self.subTest(mode=mode):
                with self.assertRaises((PlanError, ValueError)):
                    plan_debloat(changed, self.profile, self.policy, self.candidates)
                with self.assertRaises(RecordError):
                    self.save(report=changed)
                with self.assertRaises(PlanError):
                    plan_restore(self.store.load(self.plan.operation_id), changed)
        self.assertEqual(self.fake.mutating_calls, [])

    def test_unknown_install_and_unqualified_firmware_refused(self):
        metadata = {"package": "com.cleanyourcar.launcher", "version_code": 1,
                    "min_sdk": 23, "sha256": "a" * 64, "path": "launcher.apk"}
        with self.assertRaises(PlanError):
            plan_install(self.report, self.profile, metadata)
        unqualified = dict(self.policy, status="unqualified")
        with self.assertRaises(ValueError):
            plan_debloat(self.report, self.profile, unqualified, self.candidates)

    def test_install_preview_preserves_absence_and_refuses_unrecoverable_update(self):
        metadata = {"package": "com.cleanyourcar.launcher", "version_code": 1,
                    "min_sdk": 23, "sha256": "a" * 64, "path": "launcher.apk"}
        key = (self.report.identity.firmware_hash, self.plan.profile_hash, metadata["package"])
        with mock.patch("car_support.operations._TRUSTED_INSTALL_QUALIFICATIONS", frozenset({key})):
            plan = plan_install(self.report, self.profile, metadata)
            self.assertEqual([a["type"] for a in plan.actions], ["INSTALL_APK"])
            record = self.save(plan)
            self.assertIsNone(record.installed_package_identities[metadata["package"]])
            self.assertNotIn(metadata["package"], record.original_package_flags)
            changed = copy.deepcopy(self.report)
            changed.packages[metadata["package"]] = {"version_code": 1, "system": False}
            changed.package_flags[metadata["package"]] = 0
            with self.assertRaises(PlanError):
                plan_install(changed, self.profile, metadata)
            changed.unresolved_facts.append("package_flags." + metadata["package"])
            with self.assertRaises(RecordError):
                self.save(plan, changed)

    def test_setting_actions_require_exact_supported_original_and_preserve_earliest(self):
        action = {"type": "SET_SETTING", "namespace": "system", "name": "screen_brightness",
                  "value": "120"}
        with mock.patch("car_support.operations.SUPPORTED_SETTINGS",
                        frozenset({("system", "screen_brightness")})):
            plan = replace(self.plan, actions=(action,))
            with self.assertRaises(RecordError):
                self.save(plan)
            state = {"report": self.report, "settings": {"system/screen_brightness": None}}
            first = self.store.save_original(plan, state)
            state["settings"]["system/screen_brightness"] = "120"
            retry = self.store.save_original(replace(plan, operation_id="settings-retry"), state)
            self.assertIsNone(retry.original_settings["system/screen_brightness"])
            self.assertEqual(first.original_settings, retry.original_settings)
        with self.assertRaises(RecordError):
            self.store.save_original(plan, state)

    def test_journal_fsync_failure_and_bad_readback_refuse(self):
        self.save()
        with mock.patch("car_support.records.os.fsync",
                        side_effect=OSError(errno.ENOSPC, "disk full")):
            with self.assertRaises(RecordError):
                self.store.append_event(self.plan.operation_id,
                                        {"kind": "intent", "action_index": 0})
        self.assertEqual(self.fake.mutating_calls, [])
        # A possibly written intent is not evidence of a remotely completed action.
        preview = plan_restore(self.store.load(self.plan.operation_id), self.report)
        self.assertEqual(preview.actions, ())
        with self.assertRaises(RecordError):
            self.store.append_event(self.plan.operation_id,
                                    {"kind": "readback", "action_index": 0,
                                     "observed": {"enabled_flag": 1}, "confirmed": True})

    def test_intent_and_readback_are_durable_append_only(self):
        self.save()
        self.store.append_event(self.plan.operation_id,
                                {"kind": "intent", "action_index": 0})
        self.store.append_event(self.plan.operation_id,
                                {"kind": "readback", "action_index": 0,
                                 "observed": {"enabled_flag": 3}, "confirmed": True})
        record = self.store.load(self.plan.operation_id)
        self.assertEqual([e["event"]["kind"] for e in record.events], ["intent", "readback"])
        self.assertEqual(len(record.event_references), 2)
        with self.assertRaises(RecordError):
            self.store.append_event(self.plan.operation_id,
                                    {"kind": "readback", "action_index": 0,
                                     "observed": {"enabled_flag": 3}, "confirmed": True})

    def test_torn_final_journal_retains_prior_entries_without_success(self):
        self.save()
        self.store.append_event(self.plan.operation_id, {"kind": "intent", "action_index": 0})
        journal = self.root / self.plan.operation_id / "events.jsonl"
        with journal.open("ab") as handle:
            handle.write(b'{"event":{"kind":"readback"')
        recovered = self.store.load(self.plan.operation_id)
        self.assertTrue(recovered.journal_torn)
        self.assertEqual(len(recovered.events), 1)
        preview = plan_restore(recovered, self.report)
        self.assertEqual(preview.actions, ())
        self.assertTrue(any("unconfirmed" in note for note in preview.limitations))
        self.store.append_event(self.plan.operation_id,
                                {"kind": "readback", "action_index": 0,
                                 "observed": {"enabled_flag": 3}, "confirmed": True})
        self.assertFalse(self.store.load(self.plan.operation_id).journal_torn)
        self.assertTrue((journal.parent / "events.torn").exists())

    def test_corrupt_complete_journal_is_not_silently_hidden(self):
        self.save()
        journal = self.root / self.plan.operation_id / "events.jsonl"
        journal.write_bytes(b'{"garbage":true}\n')
        with self.assertRaises(RecordError):
            self.store.load(self.plan.operation_id)

    def test_restore_preview_only_confirmed_changes_and_exact_originals(self):
        self.report.package_flags[self.candidates[0]] = 0
        record = self.save()
        before = plan_restore(record, self.report)
        self.assertEqual(before.actions, ())
        self.assertTrue(before.limitations)
        self.store.append_event(self.plan.operation_id, {"kind": "intent", "action_index": 0})
        self.store.append_event(self.plan.operation_id,
                                {"kind": "readback", "action_index": 0,
                                 "observed": {"enabled_flag": 3}, "confirmed": True})
        current = copy.deepcopy(self.report)
        current.package_flags[self.candidates[0]] = 3
        preview = plan_restore(self.store.load(self.plan.operation_id), current)
        self.assertEqual(preview.actions[0]["value"], 0)
        current.package_flags[self.candidates[0]] = 1
        with self.assertRaises(PlanError):
            plan_restore(self.store.load(self.plan.operation_id), current)

    def test_permissions_and_no_raw_identifier_export(self):
        record = self.save()
        for path in [self.root] + list(self.root.rglob("*")):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o700 if path.is_dir() else 0o600)
        exported = json.dumps(record.export_summary())
        self.assertNotIn(self.report.identity.binding_hash, exported)
        self.assertNotIn("FAKEHWSN", exported)
        self.assertNotIn(self.candidates[0], exported)
        self.assertNotIn("original_home", exported)

    def test_symlinks_and_operation_path_traversal_refused(self):
        self.root.mkdir(mode=0o700)
        outside = Path(self.directory.name) / "outside"
        outside.mkdir()
        (self.root / self.plan.operation_id).symlink_to(outside, target_is_directory=True)
        with self.assertRaises(RecordError):
            self.save()
        with self.assertRaises(RecordError):
            self.store.load("../outside")
        self.assertEqual(list(outside.iterdir()), [])

    def test_symlinked_root_ancestor_refused(self):
        outside = Path(self.directory.name) / "outside"
        outside.mkdir(mode=0o700)
        link = Path(self.directory.name) / "link"
        link.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(RecordError):
            RecordStore(link / "records").save_original(self.plan, self.report)
        self.assertEqual(list(outside.iterdir()), [])

    def test_failed_readback_is_visible_in_restore_limitations(self):
        self.save()
        self.store.append_event(self.plan.operation_id, {"kind": "intent", "action_index": 0})
        self.store.append_event(self.plan.operation_id,
                                {"kind": "readback", "action_index": 0,
                                 "observed": {"enabled_flag": 1, "error": "firmware refused"},
                                 "confirmed": False})
        preview = plan_restore(self.store.load(self.plan.operation_id), self.report)
        self.assertEqual(preview.actions, ())
        self.assertTrue(any("failed" in note.lower() for note in preview.limitations))

    def test_same_operation_id_cannot_replace_original_plan(self):
        self.save()
        changed = replace(self.plan, actions=({"type": "SET_PACKAGE_ENABLED_STATE",
                                              "package": self.candidates[0], "value": 0},))
        with self.assertRaises(RecordError):
            self.save(changed)
        self.assertEqual(self.store.load(self.plan.operation_id).plan.plan_hash, self.plan.plan_hash)

    def test_post_replace_directory_sync_failure_refused(self):
        with mock.patch("car_support.records._sync_directory", wraps=__import__(
                "car_support.records", fromlist=["_sync_directory"])._sync_directory) as sync:
            def fail_snapshot_directory(path):
                if (path / "snapshot.json").exists():
                    raise OSError(errno.ENOSPC, "directory fsync failed")
                return original_sync(path)
            original_sync = sync._mock_wraps
            sync.side_effect = fail_snapshot_directory
            with self.assertRaises(RecordError):
                self.save()
        self.assertEqual(self.fake.mutating_calls, [])

    def test_firmware_change_cannot_extend_target_user_originals(self):
        self.save()
        changed = copy.deepcopy(self.report)
        changed.environment["buildProperties"]["display"] = "FAKE_CHANGED_FIRMWARE"
        from car_support.adb import environment_key
        changed.identity = replace(changed.identity, firmware_hash=environment_key(changed.environment))
        plan = replace(self.plan, operation_id="new-firmware", target=changed.identity)
        with self.assertRaises(RecordError):
            self.save(plan, changed)

    def test_restore_refuses_replaced_package_identity(self):
        self.report.package_flags[self.candidates[0]] = 0
        self.save()
        self.store.append_event(self.plan.operation_id, {"kind": "intent", "action_index": 0})
        self.store.append_event(self.plan.operation_id,
                                {"kind": "readback", "action_index": 0,
                                 "observed": {"enabled_flag": 3}, "confirmed": True})
        changed = copy.deepcopy(self.report)
        changed.package_flags[self.candidates[0]] = 3
        changed.packages[self.candidates[0]]["version_code"] = 99
        with self.assertRaises(PlanError):
            plan_restore(self.store.load(self.plan.operation_id), changed)

    def test_first_absence_is_not_replaced_by_post_install_flags(self):
        package = "com.example.newapp"
        action = {"type": "INSTALL_APK", "package": package, "version_code": 1,
                  "min_sdk": 23, "sha256": "a" * 64, "path": "new.apk"}
        plan = replace(self.plan, operation_id="new-install", operation="install", actions=(action,))
        first = self.save(plan)
        self.assertIsNone(first.installed_package_identities[package])
        changed = copy.deepcopy(self.report)
        changed.packages[package] = {"version_code": 1, "system": False}
        changed.package_flags[package] = 1
        retry = self.save(plan, changed)
        self.assertNotIn(package, retry.original_package_flags)
        next_plan = replace(self.plan, operation_id="newapp-flags", actions=(
            {"type": "SET_PACKAGE_ENABLED_STATE", "package": package, "value": 3},))
        with self.assertRaises(RecordError):
            self.save(next_plan, changed)

    def test_malformed_snapshot_and_complete_journal_duplicates_refused(self):
        self.save()
        directory = self.root / self.plan.operation_id
        self.store.append_event(self.plan.operation_id, {"kind": "intent", "action_index": 0})
        journal = directory / "events.jsonl"
        data = journal.read_bytes()
        journal.write_bytes(data.replace(b'"sequence":0', b'"sequence":false,"sequence":0'))
        with self.assertRaises(RecordError):
            self.store.load(self.plan.operation_id)

    def test_cli_restore_and_install_previews_show_limitations_and_refusals(self):
        spec = importlib.util.spec_from_file_location("task3_cli", Path(__file__).parents[1] / "car.py")
        cli = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cli)
        self.save()
        output = Path(self.directory.name) / "restore.json"
        with redirect_stdout(io.StringIO()):
            result = cli.main(["plan-restore", "--serial", "192.0.2.10:5555", "--user", "0",
                               "--operation-id", self.plan.operation_id, "--output", str(output),
                               "--records", str(self.root)], runner=self.fake)
        self.assertEqual(result, 0)
        data = json.loads(output.read_text())
        self.assertFalse(data["execution_available"])
        self.assertEqual(data["plan"]["actions"], [])
        self.assertTrue(any("No confirmed" in s for s in data["plan"]["limitations"]))
        profile = Path(self.directory.name) / "profile.json"
        metadata = Path(self.directory.name) / "metadata.json"
        profile.write_text(json.dumps(self.profile))
        metadata.write_text(json.dumps({"package": "com.cleanyourcar.launcher",
                                       "version_code": 1, "min_sdk": 23,
                                       "sha256": "a" * 64, "path": "launcher.apk"}))
        errors = io.StringIO()
        install_output = output.with_name("install.json")
        with redirect_stderr(errors):
            result = cli.main(["plan-install", "--serial", "192.0.2.10:5555", "--user", "0",
                               "--profile", str(profile), "--apk-metadata", str(metadata),
                               "--output", str(install_output), "--records", str(self.root)],
                              runner=self.fake)
        self.assertEqual(result, 1)
        self.assertIn("no trusted bundled installation qualification", errors.getvalue())
        self.assertFalse(install_output.exists())
        self.assertEqual(self.fake.mutating_calls, [])

    def test_cli_preview_requires_target_and_user_and_never_mutates(self):
        spec = importlib.util.spec_from_file_location("task3_car", Path(__file__).parents[1] / "car.py")
        cli = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cli)
        for args in (["plan-debloat"], ["plan-install"], ["plan-restore"]):
            with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                cli.main(args, runner=self.fake)
        profile = Path(self.directory.name) / "profile.json"
        policy = Path(self.directory.name) / "policy.json"
        candidates = Path(self.directory.name) / "list.txt"
        output = Path(self.directory.name) / "plan.json"
        profile.write_text(json.dumps(self.profile))
        policy.write_text(json.dumps(self.policy))
        candidates.write_text("\n".join(self.candidates))
        with redirect_stdout(io.StringIO()):
            result = cli.main(["plan-debloat", "--serial", "192.0.2.10:5555", "--user", "0",
                               "--profile", str(profile), "--policy", str(policy),
                               "--candidates", str(candidates), "--output", str(output),
                               "--records", str(self.root)], runner=self.fake)
        self.assertEqual(result, 0)
        data = json.loads(output.read_text())
        self.assertEqual(data["plan"]["target"]["binding_hash"], self.plan.target.binding_hash)
        self.assertEqual(data["confirmation_hash"], OperationPlan.from_dict(data["plan"]).confirmation_hash(
            self.store.load(data["plan"]["operation_id"])))
        self.assertEqual(self.fake.mutating_calls, [])


if __name__ == "__main__":
    unittest.main()
