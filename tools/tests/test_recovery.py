"""Offline tests for state-preserving restore and interrupted-operation recovery."""

import hashlib
import importlib.util
import io
import json
import os
import re
import shlex
import subprocess
import tempfile
from contextlib import redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest import mock

from test_operations import ExecutingAdb, fresh_responses
from test_profiles import NoProcessTestCase, qualified_case

from car_support import operations
from car_support.adb import AdbClient, AdbError, CommandResult
from car_support.profiles import discover, profile_hash
from car_support.records import RecordError, RecordStore


REPO = Path(__file__).resolve().parents[2]
SCRATCH = REPO / ".superpowers/sdd/2026-10-30-multi-device-adb-plan/scratch"
SERIAL = "192.0.2.10:5555"


class RestoringAdb(ExecutingAdb):
    """Synthetic package manager that changes only the requested user's exact flag."""

    FLAGS = {
        "enable": 1,
        "disable": 2,
        "disable-user": 3,
        "disable-until-used": 4,
        "default-state": 0,
    }

    def __init__(self, responses):
        super().__init__(responses)
        self.restore_output_prefix = ""
        self.restore_output_state = None
        self.responses["pm"]["stdout"] += (
            "  enable [--user USER_ID] PACKAGE_OR_COMPONENT\n"
            "  disable [--user USER_ID] PACKAGE_OR_COMPONENT\n"
            "  disable-until-used [--user USER_ID] PACKAGE_OR_COMPONENT\n")

    def __call__(self, argv, timeout):
        if len(argv) > 3 and argv[3] == "shell":
            tokens = shlex.split(argv[4])
            if tokens[:2] == ["pm", "uninstall"] and len(tokens) == 5:
                self.calls.append(list(argv))
                self.mutating_calls.append(list(argv))
                if tokens[2:4] != ["--user", "0"]:
                    raise AssertionError("APK removal must be scoped to the selected user")
                package = tokens[4]
                if self.outcome == "success":
                    listing = "cmd package list packages --user 0"
                    self.responses[listing]["stdout"] = "".join(
                        line for line in self.responses[listing]["stdout"].splitlines(True)
                        if line.strip() != "package:" + package)
                return CommandResult(0, "Success\n", "")
            if len(tokens) == 5 and tokens[:1] == ["pm"] and tokens[1] in self.FLAGS:
                self.calls.append(list(argv))
                self.mutating_calls.append(list(argv))
                command, user, package = tokens[1], tokens[3], tokens[4]
                if tokens[2] != "--user" or user != "0":
                    raise AssertionError("restore must scope the exact Android user")
                if self.outcome == "success":
                    dump = self.responses["dumpsys package packages"]["stdout"]
                    before, rest = dump.split("Package [%s]" % package, 1)
                    start, state = rest.split("User 0:", 1)
                    state = state.replace(
                        "enabled=%d" % self.current_flag(package),
                        "enabled=%d" % self.FLAGS[command], 1)
                    self.responses["dumpsys package packages"]["stdout"] = (
                        before + "Package [%s]" % package + start + "User 0:" + state)
                if self.on_write:
                    self.on_write()
                if self.outcome == "disconnect":
                    raise AdbError("synthetic disconnect after state change")
                if self.outcome == "empty":
                    return CommandResult(0, "", "")
                state = {
                    "default-state": "default",
                    "enable": "enabled",
                    "disable": "disabled",
                    "disable-user": "disabled-user",
                    "disable-until-used": "disabled-until-used",
                }[command]
                state = self.restore_output_state or state
                output = "%s%s new state: %s\n" % (
                    self.restore_output_prefix, package, state)
                return CommandResult(0, output, "")
        return super().__call__(argv, timeout)

    def current_flag(self, package):
        dump = self.responses["dumpsys package packages"]["stdout"]
        section = dump.split("Package [%s]" % package, 1)[1]
        import re
        match = re.search(r"User 0:.*?\benabled=(\d+)", section)
        if not match:
            raise AssertionError("synthetic package/user state is absent")
        return int(match.group(1))


class RecoveryTest(NoProcessTestCase):
    def setUp(self):
        super().setUp()
        SCRATCH.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(dir=str(SCRATCH))
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        _, self.profile, self.policy, self.candidates, _ = qualified_case()
        self.fake = RestoringAdb(fresh_responses())
        self.client = AdbClient("fake-adb", SERIAL, 0, runner=self.fake)
        self.report = discover(self.client)
        self.store = RecordStore(self.directory / "records")
        self.source_plan = operations.plan_debloat(
            self.report, self.profile, self.policy, self.candidates)

    def _set_flag(self, package, value):
        dump = self.fake.responses["dumpsys package packages"]["stdout"]
        before, rest = dump.split("Package [%s]" % package, 1)
        import re
        rest, count = re.subn(r"(User 0:.*?\benabled=)\d+", r"\g<1>%d" % value,
                              rest, count=1)
        self.assertEqual(count, 1)
        self.fake.responses["dumpsys package packages"]["stdout"] = (
            before + "Package [%s]" % package + rest)

    def _confirmed_source(self, original_flag=0):
        self._set_flag(self.candidates[0], original_flag)
        self.report = discover(self.client)
        self.source_plan = operations.plan_debloat(
            self.report, self.profile, self.policy, self.candidates)
        record = self.store.save_original(self.source_plan, self.report)
        token = self.source_plan.confirmation_hash(record)
        result = operations.apply(self.source_plan, self.client, self.store, token)
        self.assertEqual(result.status, "COMPLETED", result.to_dict())
        return self.store.load(self.source_plan.operation_id)

    def _restore(self, source_record, current=None):
        plan = operations.plan_restore(source_record, current or discover(self.client))
        record = self.store.save_original(plan, current or discover(self.client))
        token = plan.confirmation_hash(record)
        return plan, token

    def _confirmed_install(self):
        package = "com.example.newapp"
        apk = self.directory / "synthetic.apk"
        apk.write_bytes(b"synthetic APK fixture only")
        aapt2 = self.directory / "aapt2"
        aapt2.write_bytes(b"never execute this fixture")
        aapt2.chmod(0o700)
        patcher = mock.patch.dict(os.environ, {"AAPT2": str(aapt2)})
        patcher.start()
        self.addCleanup(patcher.stop)

        def metadata_runner(argv, **kwargs):
            self.assertEqual(argv, [str(aapt2), "dump", "badging", str(apk)])
            return subprocess.CompletedProcess(
                argv, 0,
                "package: name='com.example.newapp' versionCode='7' versionName='1'\n"
                "sdkVersion:'23'\n", "")

        patcher = mock.patch("subprocess.run", side_effect=metadata_runner)
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = mock.patch("car_support.operations._TRUSTED_INSTALL_QUALIFICATIONS",
                             frozenset({(self.report.identity.firmware_hash,
                                         profile_hash(self.profile), package)}))
        patcher.start()
        self.addCleanup(patcher.stop)
        metadata = {
            "package": package, "version_code": 7, "min_sdk": 23,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "path": str(apk)}
        plan = operations.plan_install(self.report, self.profile, metadata)
        record = self.store.save_original(plan, self.report)
        result = operations.apply(plan, self.client, self.store, plan.confirmation_hash(record))
        self.assertEqual(result.status, "COMPLETED", result.error)
        return self.store.load(plan.operation_id)

    def test_previously_disabled_package_stays_disabled(self):
        source = self._confirmed_source(original_flag=2)
        plan, token = self._restore(source)
        self.assertEqual(plan.actions, ({
            "type": "SET_PACKAGE_ENABLED_STATE",
            "package": self.candidates[0],
            "value": 2,
        },))
        result = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(result.status, "COMPLETED", result.to_dict())
        self.assertEqual(discover(self.client).package_flags[self.candidates[0]], 2)

    def test_previously_disabled_user_flag_is_not_enabled_or_rewritten(self):
        source = self._confirmed_source(original_flag=3)
        plan = operations.plan_restore(source, discover(self.client))
        self.assertEqual(plan.actions, ())
        record = self.store.save_original(plan, discover(self.client))
        result = operations.apply(plan, self.client, self.store, plan.confirmation_hash(record))
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(discover(self.client).package_flags[self.candidates[0]], 3)

    def test_default_state_is_not_restored_as_forced_enabled(self):
        source = self._confirmed_source(original_flag=0)
        plan, token = self._restore(source)
        self.assertEqual(plan.actions[0]["value"], 0)
        result = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(result.status, "COMPLETED", result.error)
        self.assertEqual(discover(self.client).package_flags[self.candidates[0]], 0)
        self.assertTrue(any("pm default-state --user 0" in " ".join(call)
                            for call in self.fake.mutating_calls))

    def test_restore_wrong_device_or_user_refused(self):
        source = self._confirmed_source()
        wrong_device = replace(
            discover(self.client),
            identity=replace(source.target, binding_hash="f" * 64))
        with self.assertRaises(operations.PlanError):
            operations.plan_restore(source, wrong_device)
        wrong_user = replace(discover(self.client), android_user=1,
                             identity=replace(source.target, android_user=1))
        with self.assertRaises(operations.PlanError):
            operations.plan_restore(source, wrong_user)

    def test_external_setting_change_is_conflict(self):
        source = self._confirmed_source()
        plan, token = self._restore(source)
        self._set_flag(self.candidates[0], 1)
        with self.assertRaisesRegex(operations.PlanError, "conflict|changed"):
            operations.plan_restore(source, discover(self.client))
        refused = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(refused.status, "REFUSED")
        self.assertEqual(len(self.fake.mutating_calls), 1)

    def test_external_change_to_requested_value_after_preview_is_still_conflict(self):
        source = self._confirmed_source()
        plan, token = self._restore(source)
        self._set_flag(self.candidates[0], 0)
        result = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(result.status, "REFUSED")
        self.assertIn("changed after restore preview", result.error)
        self.assertEqual(len(self.fake.mutating_calls), 1)

    def test_exact_explicit_enabled_and_until_used_states_are_restored(self):
        for flag in (1, 4):
            with self.subTest(original_flag=flag):
                store = RecordStore(self.directory / ("records-%d" % flag))
                self.store = store
                self._set_flag(self.candidates[0], flag)
                self.report = discover(self.client)
                source_plan = operations.plan_debloat(
                    self.report, self.profile, self.policy, self.candidates)
                source_snapshot = store.save_original(source_plan, self.report)
                applied = operations.apply(source_plan, self.client, store,
                                           source_plan.confirmation_hash(source_snapshot))
                self.assertEqual(applied.status, "COMPLETED", applied.error)
                source = store.load(source_plan.operation_id)
                restore_plan = operations.plan_restore(source, discover(self.client))
                self.assertEqual(restore_plan.actions[0]["value"], flag)
                restore_snapshot = store.save_original(restore_plan, discover(self.client))
                restored = operations.apply(
                    restore_plan, self.client, store,
                    restore_plan.confirmation_hash(restore_snapshot))
                self.assertEqual(restored.status, "COMPLETED", restored.error)
                self.assertEqual(discover(self.client).package_flags[self.candidates[0]], flag)

    def test_initial_mutation_refuses_when_exact_inverse_is_unavailable(self):
        required = {0: "default_state", 1: "enable", 2: "disable",
                    3: "disable_user", 4: "disable_until_used"}
        for flag, capability in required.items():
            with self.subTest(flag=flag, capability=capability):
                self._set_flag(self.candidates[0], flag)
                report = discover(self.client)
                capabilities = dict(report.command_capabilities)
                capabilities[capability] = False
                unsupported = replace(report, command_capabilities=capabilities)
                with self.assertRaisesRegex(operations.PlanError,
                                            "no probed restore command"):
                    operations.plan_debloat(
                        unsupported, self.profile, self.policy, self.candidates)
        self.assertEqual(self.fake.mutating_calls, [])

    def test_initial_mutation_refuses_when_help_lacks_per_user_inverse(self):
        cases = {
            0: ("default-state", "default_state"),
            1: ("enable", "enable"),
            2: ("disable", "disable"),
            3: ("disable-user", "disable_user"),
            4: ("disable-until-used", "disable_until_used"),
        }
        original_help = self.fake.responses["pm"]["stdout"]
        for flag, (command, capability) in cases.items():
            with self.subTest(flag=flag, command=command):
                self.fake.responses["pm"]["stdout"] = original_help
                lines = []
                found = False
                for line in original_help.splitlines():
                    if re.match(r"^\s*" + re.escape(command) + r"(?=\s|$)", line):
                        line = line.replace("[--user USER_ID] ", "")
                        found = True
                    lines.append(line)
                self.assertTrue(found, command)
                self.fake.responses["pm"]["stdout"] = "\n".join(lines) + "\n"
                self._set_flag(self.candidates[0], flag)
                report = discover(self.client)
                self.assertFalse(report.command_capabilities[capability])
                with self.assertRaisesRegex(
                        operations.PlanError, "no probed restore command"):
                    operations.plan_debloat(
                        report, self.profile, self.policy, self.candidates)
        self.assertEqual(self.fake.mutating_calls, [])

    def test_restore_accepts_prefixed_state_output_but_rejects_mismatch(self):
        source = self._confirmed_source(original_flag=0)
        plan, token = self._restore(source)
        self.fake.restore_output_prefix = "Package "
        result = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(result.status, "COMPLETED", result.error)

        source = self._confirmed_source(original_flag=0)
        plan, token = self._restore(source)
        self.fake.restore_output_state = "enabled"
        mismatch = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(mismatch.status, "FAILED")
        self.assertEqual(discover(self.client).package_flags[self.candidates[0]], 0)

    def test_restore_rejects_expected_output_without_exact_state_readback(self):
        source = self._confirmed_source(original_flag=0)
        plan, token = self._restore(source)
        self.fake.outcome = "no-change"
        result = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(result.status, "FAILED")
        self.assertEqual(discover(self.client).package_flags[self.candidates[0]], 3)

    def test_crash_after_write_before_result_uses_fresh_readback(self):
        record = self.store.save_original(self.source_plan, self.report)
        token = self.source_plan.confirmation_hash(record)
        append = self.store.append_event

        def lose_result(operation_id, event):
            if event["kind"] == "readback":
                raise RecordError("synthetic crash before durable result")
            return append(operation_id, event)

        with mock.patch.object(self.store, "append_event", side_effect=lose_result):
            interrupted = operations.apply(self.source_plan, self.client, self.store, token)
        self.assertEqual(interrupted.status, "PARTIAL")
        self.assertEqual(self.fake.current_flag(self.candidates[0]), 3)
        source = self.store.load(self.source_plan.operation_id)
        self.assertEqual(source.events[-1]["event"], {
            "kind": "intent", "action_index": 0})

        restore_plan, restore_token = self._restore(source)
        self.assertEqual(restore_plan.actions[0]["value"], 0)
        result = operations.apply(restore_plan, self.client, self.store, restore_token)
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(discover(self.client).package_flags[self.candidates[0]], 0)
        recovered_source = self.store.load(source.operation_id)
        self.assertTrue(recovered_source.events[-1]["event"]["confirmed"])

    def test_restore_retry_is_idempotent(self):
        source = self._confirmed_source()
        plan, token = self._restore(source)
        first = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(first.status, "COMPLETED", first.error)
        writes_after_first = len(self.fake.mutating_calls)
        second = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(second.status, "COMPLETED")
        self.assertEqual(len(self.fake.mutating_calls), writes_after_first)
        self.assertEqual(discover(self.client).package_flags[self.candidates[0]], 0)
        next_plan = operations.plan_restore(source, discover(self.client))
        self.assertEqual(next_plan.actions, ())
        next_snapshot = self.store.save_original(next_plan, discover(self.client))
        next_result = operations.apply(
            next_plan, self.client, self.store, next_plan.confirmation_hash(next_snapshot))
        self.assertEqual(next_result.status, "COMPLETED")
        self._set_flag(self.candidates[0], 1)
        external = operations.apply(
            next_plan, self.client, self.store, next_plan.confirmation_hash(next_snapshot))
        self.assertEqual(external.status, "REFUSED")

    def test_interrupted_restore_uses_exact_fresh_readback_without_reissuing(self):
        source = self._confirmed_source()
        plan, token = self._restore(source)
        append = self.store.append_event

        def lose_restore_result(operation_id, event):
            if operation_id == plan.operation_id and event["kind"] == "readback":
                raise RecordError("synthetic crash after restore readback")
            return append(operation_id, event)

        with mock.patch.object(self.store, "append_event", side_effect=lose_restore_result):
            interrupted = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(interrupted.status, "PARTIAL")
        self.assertEqual(self.fake.current_flag(self.candidates[0]), 0)
        writes_before_retry = len(self.fake.mutating_calls)
        retried = operations.apply(plan, self.client, self.store, token)
        self.assertEqual(retried.status, "COMPLETED", retried.error)
        self.assertEqual(len(self.fake.mutating_calls), writes_before_retry)

    def test_unavailable_apk_rollback_is_reported_not_faked(self):
        source = self._confirmed_install()
        restore = operations.plan_restore(source, discover(self.client))
        self.assertEqual(restore.actions, ())
        self.assertTrue(any("separate plan and confirmation" in item
                            for item in restore.limitations))
        with self.assertRaisesRegex(operations.PlanError, "manual recovery"):
            operations.plan_remove_apk(source, discover(self.client), self.client)

    def test_fresh_apk_removal_needs_its_own_plan_and_confirmation(self):
        source = self._confirmed_install()
        self.fake.responses["pm"]["stdout"] += (
            "  uninstall [-k] [--user USER_ID] PACKAGE\n")
        cli_spec = importlib.util.spec_from_file_location(
            "task5_car_cli", Path(__file__).parents[1] / "car.py")
        cli = importlib.util.module_from_spec(cli_spec)
        cli_spec.loader.exec_module(cli)
        preview_path = self.directory / "remove-plan.json"
        with redirect_stdout(io.StringIO()):
            planned = cli.main([
                "plan-remove-apk", "--serial", SERIAL, "--user", "0",
                "--operation-id", source.operation_id, "--output", str(preview_path),
                "--records", str(self.store.root),
            ], runner=self.fake)
        self.assertEqual(planned, 0)
        preview = json.loads(preview_path.read_text(encoding="utf-8"))
        self.assertTrue(preview["execution_available"])
        self.assertEqual(preview["plan"]["operation"], "remove")
        self.assertEqual(preview["plan"]["actions"], [{
            "type": "UNINSTALL_FRESH_APK", "package": "com.example.newapp",
            "version_code": 7,
        }])
        with redirect_stdout(io.StringIO()):
            applied = cli.main([
                "apply", "--serial", SERIAL, "--user", "0",
                "--records", str(self.store.root), "--plan", str(preview_path),
                "--confirm", preview["confirmation_hash"],
            ], runner=self.fake)
        self.assertEqual(applied, 0)
        self.assertNotIn("com.example.newapp", discover(self.client).packages)


if __name__ == "__main__":
    import unittest
    unittest.main()
