"""Offline executor contracts: real records/planners, synthetic transport and APK bytes."""
import copy
import hashlib
import importlib.util
import io
import json
import os
import shlex
import subprocess
import tempfile
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import replace
from pathlib import Path
from unittest import mock

from test_profiles import NoProcessTestCase, NET, qualified_case, trusted_policy
from fake_adb import FakeAdb
from car_support import operations
from car_support.adb import AdbClient, AdbError, CommandResult
from car_support.profiles import discover, candidate_list_hash, profile_hash
from car_support.records import RecordStore, RecordError, write_private_json


REPO = Path(__file__).resolve().parents[2]
SCRATCH = REPO / ".superpowers/sdd/2026-10-30-multi-device-adb-plan/scratch"


def fresh_responses():
    responses = copy.deepcopy(qualified_case()[4].responses)
    responses["dumpsys package packages"]["stdout"] = responses[
        "dumpsys package packages"]["stdout"].replace("enabled=3", "enabled=0")
    return responses


class ExecutingAdb(FakeAdb):
    def __init__(self, responses):
        super().__init__(responses)
        self.outcome = "success"
        self.install_output = "Success\n"
        self.on_write = None

    def add_package(self, package, version=1, flag=0, user=0):
        dump = self.responses["dumpsys package packages"]["stdout"]
        dump += ("\n  Package [%s] (abc):\n    versionCode=%d\n"
                 "    pkgFlags=[ HAS_CODE ]\n"
                 "    User %d: installed=true hidden=false stopped=false enabled=%d\n"
                 % (package, version, user, flag))
        self.responses["dumpsys package packages"]["stdout"] = dump
        if user == 0:
            key = "cmd package list packages --user 0"
            self.responses[key]["stdout"] += "package:%s\n" % package

    def __call__(self, argv, timeout):
        tokens = shlex.split(argv[4]) if argv[3] == "shell" else argv[3:]
        mutation = tokens[:2] == ["pm", "disable-user"] or tokens[:1] == ["install"]
        if not mutation:
            return super().__call__(argv, timeout)
        self.calls.append(list(argv))
        self.mutating_calls.append(list(argv))
        if tokens[:2] == ["pm", "disable-user"]:
            if tokens[:4] != ["pm", "disable-user", "--user", "0"] or len(tokens) != 5:
                raise AssertionError("unexpected package mutation: %r" % tokens)
            package = tokens[4]
            if self.outcome == "success":
                dump = self.responses["dumpsys package packages"]["stdout"]
                before, rest = dump.split("Package [%s]" % package, 1)
                rest = rest.replace("enabled=0", "enabled=3", 1)
                self.responses["dumpsys package packages"]["stdout"] = before + "Package [%s]" % package + rest
            output = "%s new state: disabled-user\n" % package
        else:
            if tokens[:3] != ["install", "--user", "0"] or len(tokens) != 4:
                raise AssertionError("unexpected install flags: %r" % tokens)
            if self.outcome == "success":
                self.add_package("com.example.newapp", version=7)
            output = self.install_output
        if self.on_write:
            self.on_write()
        if self.outcome == "disconnect":
            raise AdbError("synthetic disconnect")
        if self.outcome == "refused":
            return CommandResult(0, "Failure [INSTALL_FAILED_USER_RESTRICTED]\n", "")
        if self.outcome == "empty":
            return CommandResult(0, "", "")
        return CommandResult(0, output, "")


class OperationsTest(NoProcessTestCase):
    def setUp(self):
        super().setUp()
        SCRATCH.mkdir(parents=True, exist_ok=True)
        directory = tempfile.TemporaryDirectory(dir=str(SCRATCH))
        self.addCleanup(directory.cleanup)
        self.directory = Path(directory.name)
        self.report, self.profile, self.policy, self.candidates, _ = qualified_case()
        self.fake = ExecutingAdb(fresh_responses())
        self.client = AdbClient("fake-adb", NET, 0, runner=self.fake)
        self.report = discover(self.client)
        self.store = RecordStore(self.directory / "records")
        self.plan = operations.plan_debloat(self.report, self.profile, self.policy, self.candidates)

    def save(self, plan=None):
        plan = plan or self.plan
        record = self.store.save_original(plan, discover(self.client))
        return plan.confirmation_hash(record)

    def apply(self, plan=None, token=None):
        self.assertTrue(callable(getattr(operations, "apply", None)), "apply executor is missing")
        return operations.apply(plan or self.plan, self.client, self.store,
                                token if token is not None else self.save(plan))

    def install_fixture(self):
        apk = self.directory / "new app.apk"
        apk.write_bytes(b"synthetic APK fixture only")
        aapt2 = self.directory / "aapt2"
        aapt2.write_bytes(b"never execute this fixture")
        aapt2.chmod(0o700)
        patcher = mock.patch.dict(os.environ, {"AAPT2": str(aapt2)})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.badging = ("package: name='com.example.newapp' versionCode='7' versionName='1'\n"
                        "sdkVersion:'23'\n")

        def metadata_runner(argv, **kwargs):
            self.assertEqual(argv, [str(aapt2), "dump", "badging", str(apk)])
            self.assertFalse(kwargs["shell"])
            self.assertLessEqual(kwargs["timeout"], 120)
            return subprocess.CompletedProcess(argv, 0, self.badging, "")

        patcher = mock.patch("subprocess.run", side_effect=metadata_runner)
        patcher.start()
        self.addCleanup(patcher.stop)
        patcher = mock.patch("car_support.operations._TRUSTED_INSTALL_QUALIFICATIONS", frozenset({
            (self.report.identity.firmware_hash, profile_hash(self.profile), "com.example.newapp")}))
        patcher.start()
        self.addCleanup(patcher.stop)
        metadata = {"package": "com.example.newapp", "version_code": 7, "min_sdk": 23,
                    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "path": str(apk)}
        return metadata

    def test_no_snapshot_or_stale_hash_refuses(self):
        result = self.apply(token="a" * 64)
        self.assertEqual(result.status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])
        self.save()
        result = self.apply(token="b" * 64)
        self.assertEqual(result.status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_completed_has_exact_states_and_durable_intent_under_batch_lock(self):
        token = self.save()

        def check_boundary():
            record = self.store.load(self.plan.operation_id)
            self.assertEqual(record.events[-1]["event"], {"kind": "intent", "action_index": 0})
            with self.assertRaises(RecordError):
                with RecordStore(self.store.root).lock(self.plan.target):
                    pass

        self.fake.on_write = check_boundary
        result = self.apply(token=token)
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(result.operation_id, self.plan.operation_id)
        self.assertEqual(result.actions[0]["expected_before"], {"enabled_flag": 0})
        self.assertEqual(result.actions[0]["observed_before"], {"enabled_flag": 0})
        self.assertEqual(result.actions[0]["expected_after"], {"enabled_flag": 3})
        self.assertEqual(result.actions[0]["observed_after"]["enabled_flag"], 3)
        self.assertTrue(result.actions[0]["recovery_reference"])
        self.assertEqual(len(self.fake.mutating_calls), 1)
        record = self.store.load(self.plan.operation_id)
        self.assertEqual([e["event"]["kind"] for e in record.events], ["intent", "readback"])
        self.assertTrue(record.events[-1]["event"]["confirmed"])

    def test_target_or_firmware_change_stops_before_next_write(self):
        for property_name, value in (("ro.serialno", "DIFFERENT-HARDWARE"),
                                     ("ro.build.display.id", "NEW-FIRMWARE")):
            with self.subTest(property=property_name):
                self.fake.responses = fresh_responses()
                self.fake.add_package("com.example.second")
                candidates = self.candidates + ["com.example.second"]
                policy = copy.deepcopy(self.policy)
                policy["candidateListHash"] = candidate_list_hash(candidates)
                with trusted_policy(policy):
                    plan = operations.plan_debloat(discover(self.client), self.profile, policy, candidates)
                    token = self.save(plan)
                    append = self.store.append_event

                    def change_after_verified_result(operation_id, event):
                        reference = append(operation_id, event)
                        if event["kind"] == "readback" and event["confirmed"]:
                            self.fake.responses.update(
                                {"getprop " + property_name: {"stdout": value + "\n"}})
                        return reference

                    count = len(self.fake.mutating_calls)
                    with mock.patch.object(self.store, "append_event",
                                           side_effect=change_after_verified_result):
                        result = self.apply(plan, token)
                    self.assertEqual(result.status, "PARTIAL")
                    self.assertEqual(len(result.actions), 2)
                    self.assertEqual(len(self.fake.mutating_calls) - count, 1)
                    self.assertTrue(result.recovery_references)

    def test_zero_exit_but_still_enabled_is_failure(self):
        self.fake.outcome = "unchanged"
        result = self.apply()
        self.assertEqual(result.status, "FAILED")
        self.assertEqual(result.actions[0]["observed_after"]["enabled_flag"], 0)
        self.assertFalse(self.store.load(self.plan.operation_id).events[-1]["event"]["confirmed"])

    def test_missing_package_or_refused_pm_is_not_success(self):
        token = self.save()
        key = "cmd package list packages --user 0"
        original = self.fake.responses[key]["stdout"]
        self.fake.responses[key]["stdout"] = original.replace("package:com.example.fakebloat\n", "")
        result = self.apply(token=token)
        self.assertEqual(result.status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])
        self.fake.responses[key]["stdout"] = original
        self.fake.outcome = "refused"
        result = self.apply(token=token)
        self.assertEqual(result.status, "FAILED")
        self.assertFalse(self.store.load(self.plan.operation_id).events[-1]["event"]["confirmed"])

    def test_empty_command_success_and_disconnect_remain_unknown(self):
        for outcome in ("empty", "disconnect"):
            with self.subTest(outcome=outcome):
                plan = replace(self.plan, operation_id="outcome-" + outcome)
                self.fake.outcome = outcome
                result = self.apply(plan)
                self.assertEqual(result.status, "FAILED")
                self.assertTrue(result.recovery_references)
                self.assertFalse(self.store.load(plan.operation_id).events[-1]["event"]["confirmed"])

    def test_tampered_originals_or_failed_durability_never_authorize_write(self):
        token = self.save()
        with mock.patch("car_support.records.os.fsync", side_effect=OSError("sync failed")):
            result = self.apply(token=token)
        self.assertEqual(result.status, "REFUSED")
        snapshot = self.store.root / self.plan.operation_id / "snapshot.json"
        data = json.loads(snapshot.read_text())
        data["original_package_flags"][self.candidates[0]] = 1
        snapshot.write_text(json.dumps(data))
        result = self.apply(token=token)
        self.assertEqual(result.status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_intent_sync_failure_never_sends_command(self):
        token = self.save()
        with mock.patch.object(self.store, "append_event", side_effect=RecordError("disk full")):
            result = self.apply(token=token)
        self.assertEqual(result.status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_identity_or_qualification_changed_after_intent_prevents_command(self):
        token = self.save()
        append = self.store.append_event

        def change_after_intent(operation_id, event):
            reference = append(operation_id, event)
            self.fake.responses["getprop ro.serialno"] = {"stdout": "OTHER-HARDWARE\n"}
            return reference

        with mock.patch.object(self.store, "append_event", side_effect=change_after_intent):
            result = self.apply(token=token)
        self.assertEqual(result.status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])
        self.assertEqual(self.store.load(self.plan.operation_id).events[-1]["event"]["kind"], "intent")

    def test_journal_durability_is_reestablished_on_load(self):
        self.save()
        self.store.append_event(self.plan.operation_id, {"kind": "intent", "action_index": 0})
        journal = self.store.root / self.plan.operation_id / "events.jsonl"
        inode = journal.stat().st_ino
        fsync = os.fsync

        def journal_sync_failure(fd):
            if os.fstat(fd).st_ino == inode:
                raise OSError("journal fsync unavailable")
            return fsync(fd)

        with mock.patch("car_support.records.os.fsync", side_effect=journal_sync_failure):
            with self.assertRaises(RecordError):
                self.store.load(self.plan.operation_id)

    def test_readback_persistence_failure_reports_recovery_not_completed(self):
        token = self.save()
        append = self.store.append_event

        def fail_readback(operation_id, event):
            if event["kind"] == "readback":
                raise RecordError("disk full after command")
            return append(operation_id, event)

        with mock.patch.object(self.store, "append_event", side_effect=fail_readback):
            result = self.apply(token=token)
        self.assertEqual(result.status, "PARTIAL")
        self.assertEqual(self.store.load(self.plan.operation_id).events[-1]["event"]["kind"], "intent")
        self.assertTrue(result.recovery_references)

    def test_pending_torn_or_previously_applied_plan_is_never_replayed(self):
        token = self.save()
        self.store.append_event(self.plan.operation_id, {"kind": "intent", "action_index": 0})
        result = self.apply(token=token)
        self.assertEqual(result.status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])
        journal = self.store.root / self.plan.operation_id / "events.jsonl"
        with journal.open("ab") as handle:
            handle.write(b'{"unfinished":')
        self.assertEqual(self.apply(token=token).status, "REFUSED")

    def test_qualification_revocation_and_missing_proof_refuse(self):
        token = self.save()
        with mock.patch("car_support.profiles._TRUSTED_QUALIFIED_POLICY_HASHES", frozenset()):
            result = self.apply(token=token)
        self.assertEqual(result.status, "REFUSED")
        preconditions = copy.deepcopy(self.plan.preconditions)
        preconditions.pop("write_qualification", None)
        old = replace(self.plan, operation_id="no-proof", preconditions=preconditions)
        self.assertEqual(self.apply(old).status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_changed_package_home_or_user_preconditions_refuse(self):
        token = self.save()
        self.fake.add_package("com.example.fakebloat", version=999)
        self.assertEqual(self.apply(token=token).status, "REFUSED")
        self.fake.responses = fresh_responses()
        self.client.android_user = 10
        self.assertEqual(self.apply(token=token).status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_other_users_correct_state_does_not_mask_requested_user_failure(self):
        token = self.save()
        dump = self.fake.responses["dumpsys package packages"]["stdout"]
        dump += ("    User 10: installed=true hidden=false enabled=3\n")
        self.fake.responses["dumpsys package packages"]["stdout"] = dump
        self.fake.outcome = "unchanged"
        result = self.apply(token=token)
        self.assertEqual(result.status, "FAILED")
        self.assertEqual(result.actions[0]["observed_after"]["enabled_flag"], 0)

    def test_home_change_or_missing_disable_user_capability_refuses(self):
        token = self.save()
        self.fake.responses["pm"]["stdout"] = "list packages --user USER_ID\n"
        self.fake.responses["pm"]["stderr"] = ""
        self.assertEqual(self.apply(token=token).status, "REFUSED")
        self.fake.responses = fresh_responses()
        for key, value in self.fake.responses.items():
            if key.startswith("cmd package resolve-activity"):
                value["stdout"] = "com.example.fakehome/.Home\n"
        self.assertEqual(self.apply(token=token).status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_revoked_or_changed_proof_between_actions_stops_batch(self):
        self.fake.add_package("com.example.second")
        candidates = self.candidates + ["com.example.second"]
        policy = dict(self.policy, candidateListHash=candidate_list_hash(candidates))
        append = self.store.append_event
        with trusted_policy(policy):
            plan = operations.plan_debloat(discover(self.client), self.profile, policy, candidates)
            token = self.save(plan)

            def revoke_after_first(operation_id, event):
                reference = append(operation_id, event)
                if event["kind"] == "readback":
                    import car_support.profiles as profiles
                    profiles._TRUSTED_QUALIFIED_POLICY_HASHES = frozenset()
                return reference

            with mock.patch.object(self.store, "append_event", side_effect=revoke_after_first):
                result = self.apply(plan, token)
        self.assertEqual(result.status, "PARTIAL")
        self.assertEqual(len(self.fake.mutating_calls), 1)

    def test_unsupported_settings_restore_or_non_disable_actions_refuse(self):
        for operation, value in (("restore", 3), ("debloat", 1)):
            with self.subTest(operation=operation):
                plan = replace(self.plan, operation_id="unsupported-" + operation, operation=operation,
                               actions=(dict(self.plan.actions[0], value=value),))
                self.assertEqual(self.apply(plan).status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_install_does_not_enable_bridge_or_change_home(self):
        metadata = self.install_fixture()
        plan = operations.plan_install(self.report, self.profile, metadata)
        result = self.apply(plan)
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(self.fake.mutating_calls,
                         [["fake-adb", "-s", NET, "install", "--user", "0", metadata["path"]]])
        self.assertEqual(self.store.load(plan.operation_id).installed_package_identities,
                         {"com.example.newapp": None})
        self.assertEqual(result.actions[0]["observed_after"]["version_code"], 7)

    def test_streamed_install_success_still_requires_exact_verified_readback(self):
        metadata = self.install_fixture()
        plan = operations.plan_install(self.report, self.profile, metadata)
        self.fake.install_output = "Performing Streamed Install\nSuccess\n"
        self.assertEqual(self.apply(plan).status, "COMPLETED")
        self.fake.responses = fresh_responses()
        plan = operations.plan_install(self.report, self.profile, metadata)
        self.fake.outcome = "unchanged"
        self.assertEqual(self.apply(plan).status, "FAILED")

    def test_unrecoverable_update_is_refused(self):
        metadata = self.install_fixture()
        report = copy.deepcopy(self.report)
        report.packages[metadata["package"]] = {"system": False, "version_code": 6}
        report.package_flags[metadata["package"]] = 0
        with self.assertRaises(operations.PlanError):
            operations.plan_install(report, self.profile, metadata)

    def test_actual_apk_hash_metadata_and_missing_tool_refuse_at_plan(self):
        metadata = self.install_fixture()
        for key, value in (("sha256", "a" * 64), ("package", "com.example.other"),
                           ("version_code", 8), ("min_sdk", 31), ("path", "missing.apk")):
            with self.subTest(field=key), self.assertRaises(operations.PlanError):
                operations.plan_install(self.report, self.profile, dict(metadata, **{key: value}))
        self.assertTrue(callable(getattr(operations, "find_aapt2", None)),
                        "APK inspector/tool discovery is missing")
        with mock.patch("car_support.operations.find_aapt2", return_value=None):
            with self.assertRaises(operations.PlanError):
                operations.plan_install(self.report, self.profile, metadata)

    def test_badging_failure_duplicate_or_missing_metadata_refuses(self):
        metadata = self.install_fixture()
        for output in ("", "package: name='com.example.newapp' versionCode='7'\n",
                       self.badging + "sdkVersion:'23'\n",
                       self.badging + "package: name='com.example.newapp' versionCode='7'\n"):
            with self.subTest(output=output), self.assertRaises(operations.PlanError):
                self.badging = output
                operations.plan_install(self.report, self.profile, metadata)
        with mock.patch("subprocess.run", side_effect=subprocess.TimeoutExpired("aapt2", 30)):
            with self.assertRaises(operations.PlanError):
                operations.plan_install(self.report, self.profile, metadata)

    def test_tool_discovery_uses_existing_sdk_without_running_it(self):
        sdk = self.directory / "sdk"
        for version in ("36.0.0", "37.0.0"):
            tool = sdk / "build-tools" / version / "aapt2"
            tool.parent.mkdir(parents=True)
            tool.write_text("synthetic executable; never run")
            tool.chmod(0o700)
        with mock.patch.dict(os.environ, {"ANDROID_SDK_ROOT": str(sdk), "AAPT2": ""}):
            self.assertEqual(operations.find_aapt2(), str(sdk / "build-tools/37.0.0/aapt2"))
        with mock.patch.dict(os.environ, {"AAPT2": str(sdk / "missing")}):
            self.assertIsNone(operations.find_aapt2())

    def test_artifact_changes_or_global_existing_package_refuse_at_apply(self):
        metadata = self.install_fixture()
        plan = operations.plan_install(self.report, self.profile, metadata)
        token = self.save(plan)
        apk = Path(metadata["path"])
        original = apk.read_bytes()
        apk.write_bytes(b"changed bytes")
        self.assertEqual(self.apply(plan, token).status, "REFUSED")
        apk.write_bytes(original)
        self.fake.add_package("com.example.newapp", version=6, user=10)
        self.assertEqual(self.apply(plan, token).status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_install_missing_readback_and_wrong_version_fail(self):
        metadata = self.install_fixture()
        for outcome in ("unchanged", "success"):
            with self.subTest(outcome=outcome):
                self.fake.responses = fresh_responses()
                plan = operations.plan_install(discover(self.client), self.profile, metadata)
                self.fake.outcome = outcome
                if outcome == "success":
                    self.fake.on_write = lambda: self.fake.responses["dumpsys package packages"].update(
                        stdout=self.fake.responses["dumpsys package packages"]["stdout"].replace(
                            "versionCode=7", "versionCode=8"))
                result = self.apply(plan)
                self.assertEqual(result.status, "FAILED")

    def test_production_install_qualification_cannot_be_waived(self):
        metadata = self.install_fixture()
        with mock.patch("car_support.operations._TRUSTED_INSTALL_QUALIFICATIONS", frozenset()):
            with self.assertRaises(operations.PlanError):
                operations.plan_install(self.report, self.profile, metadata)
        plan = operations.plan_install(self.report, self.profile, metadata)
        token = self.save(plan)
        with mock.patch("car_support.operations._TRUSTED_INSTALL_QUALIFICATIONS", frozenset()):
            self.assertEqual(self.apply(plan, token).status, "REFUSED")
        self.assertEqual(self.fake.mutating_calls, [])

    def test_cli_requires_saved_plan_confirmation_serial_user_and_honest_exit(self):
        spec = importlib.util.spec_from_file_location("task4_car", REPO / "tools/car.py")
        car = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(car)
        self.assertIn("apply", car._parser()._subparsers._group_actions[0].choices,
                      "explicit apply CLI is missing")
        token = self.save()
        record = self.store.load(self.plan.operation_id)
        preview = self.directory / "plan.json"
        write_private_json(preview, {"plan": self.plan.to_dict(), "plan_hash": self.plan.plan_hash,
                                    "originals": record.snapshot_dict(), "record_hash": record.record_hash,
                                    "confirmation_hash": token})
        args = ["apply", "--serial", NET, "--user", "0", "--records", str(self.store.root),
                "--plan", str(preview), "--confirm", token]
        for option in ("--serial", "--user", "--plan", "--confirm"):
            index = args.index(option)
            with self.subTest(missing=option), redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as error:
                    car.main(args[:index] + args[index + 2:], runner=self.fake)
                self.assertEqual(error.exception.code, 2)
        self.fake.outcome = "unchanged"
        output = io.StringIO()
        with redirect_stdout(output), redirect_stderr(io.StringIO()):
            code = car.main(args, runner=self.fake)
        self.assertNotEqual(code, 0)
        self.assertIn("FAILED", output.getvalue())
        self.assertIn(self.plan.operation_id, output.getvalue())
        self.assertIn("recovery", output.getvalue().lower())
        self.assertEqual(len(self.fake.mutating_calls), 1)

    def test_cli_modified_preview_originals_cannot_bypass_store(self):
        self.assertTrue(hasattr(self.store, "load_plan"), "trusted saved-plan reader is missing")
        token = self.save()
        record = self.store.load(self.plan.operation_id)
        path = self.directory / "plan.json"
        data = {"plan": self.plan.to_dict(), "plan_hash": self.plan.plan_hash,
                "originals": record.snapshot_dict(), "record_hash": record.record_hash,
                "confirmation_hash": token}
        write_private_json(path, data)
        self.assertEqual(self.store.load_plan(path).plan_hash, self.plan.plan_hash)
        data["originals"]["original_package_flags"][self.candidates[0]] = 1
        write_private_json(path, data)
        with self.assertRaises(RecordError):
            self.store.load_plan(path)

    def test_saved_preview_reader_refuses_public_parent_directory(self):
        self.save()
        record = self.store.load(self.plan.operation_id)
        parent = self.directory / "public"
        parent.mkdir(mode=0o755)
        parent.chmod(0o755)
        path = parent / "plan.json"
        data = {"plan": self.plan.to_dict(), "plan_hash": self.plan.plan_hash,
                "originals": record.snapshot_dict(), "record_hash": record.record_hash,
                "confirmation_hash": self.plan.confirmation_hash(record)}
        path.write_text(json.dumps(data))
        path.chmod(0o600)
        with self.assertRaises(RecordError):
            self.store.load_plan(path)

    def test_cli_completed_refused_partial_and_missing_adb_exit_codes(self):
        spec = importlib.util.spec_from_file_location("task4_status_car", REPO / "tools/car.py")
        car = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(car)
        token = self.save()
        record = self.store.load(self.plan.operation_id)
        path = self.directory / "status-plan.json"
        write_private_json(path, {"plan": self.plan.to_dict(), "plan_hash": self.plan.plan_hash,
                                 "originals": record.snapshot_dict(), "record_hash": record.record_hash,
                                 "confirmation_hash": token})
        args = ["apply", "--serial", NET, "--user", "0", "--records", str(self.store.root),
                "--plan", str(path), "--confirm", token]
        for status, code in (("COMPLETED", 0), ("REFUSED", 2), ("PARTIAL", 3), ("FAILED", 1)):
            result = operations.ApplyResult(status, self.plan.operation_id, (), ("snapshot.json",))
            output = io.StringIO()
            with mock.patch.object(car, "apply", return_value=result), redirect_stdout(output):
                self.assertEqual(car.main(args, runner=self.fake), code)
            self.assertEqual(json.loads(output.getvalue())["status"], status)
        with mock.patch.dict(os.environ, {"ADB": "", "PATH": ""}), \
                mock.patch.object(car.shutil, "which", return_value=None), redirect_stdout(io.StringIO()):
            self.assertEqual(car.main(args), 2)
        self.assertEqual(self.fake.mutating_calls, [])
