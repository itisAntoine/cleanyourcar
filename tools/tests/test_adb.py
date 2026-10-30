import contextlib
import copy
import hashlib
import io
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import car  # noqa: E402
from car_support.adb import AdbClient, AdbError, CommandResult, IdentityUnavailable  # noqa: E402
from car_support.profiles import DiscoveryError, discover  # noqa: E402
from fake_adb import FakeAdb, load_fixture  # noqa: E402

USB = "FAKE-USB-0001"
NET = "192.0.2.10:5555"
HOME_QUERY = ("cmd package query-activities --brief --user 0 -a android.intent.action.MAIN "
              "-c android.intent.category.HOME")
HOME_RESOLVE = ("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN "
                "-c android.intent.category.HOME")
SCRATCH = Path(__file__).resolve().parent / "_scratch"


def api23():
    return load_fixture("discovery-api23-aftermarket.json")


def api30():
    return load_fixture("discovery-api30-aftermarket.json")


def java_environment_key(platform, sdk, props):
    canonical = "platform=%s\nsdk=%d\n" % (platform, sdk) + "".join(
        "%s=%d:%s\n" % (key, len(props[key]), props[key]) for key in sorted(props))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def client_for(fake, serial=NET, user=0):
    return AdbClient("fake-adb", serial, user, runner=fake)


class NoRealAdbTestCase(unittest.TestCase):
    """Any attempt to spawn a real process fails the test; PATH has no adb."""

    def setUp(self):
        for name in ("run", "Popen", "call", "check_call", "check_output"):
            patcher = mock.patch.object(subprocess, name,
                                        side_effect=AssertionError("real process launched"))
            patcher.start()
            self.addCleanup(patcher.stop)
        env = mock.patch.dict(os.environ, {"PATH": "", "ANDROID_SERIAL": USB})
        env.start()
        self.addCleanup(env.stop)
        os.environ.pop("ADB", None)


class TargetingTest(NoRealAdbTestCase):

    def test_requires_explicit_serial_and_user(self):
        fake = FakeAdb(api30())
        for serial in (None, "", " ", "-s", "a b", "x;reboot", "dev\n"):
            with self.subTest(serial=serial), self.assertRaises(ValueError):
                AdbClient("fake-adb", serial, 0, runner=fake)
        for user in (None, -1, "0", True, 1.0):
            with self.subTest(user=user), self.assertRaises(ValueError):
                AdbClient("fake-adb", USB, user, runner=fake)
        for argv in (["discover", "--user", "0", "--output", "r.json"],
                     ["discover", "--serial", USB, "--output", "r.json"],
                     ["discover", "--serial", USB, "--user", "0"]):
            with self.subTest(argv=argv), self.assertRaises(SystemExit) as raised, \
                    contextlib.redirect_stderr(io.StringIO()):
                car.main(argv, runner=fake)
            self.assertEqual(raised.exception.code, 2)
        self.assertEqual(fake.calls, [])

    def test_never_selects_first_connected_device(self):
        fake = FakeAdb(api30(), connected=(USB, NET))
        discover(client_for(fake, serial=NET))
        self.assertEqual(fake.device_listing_calls, [])
        self.assertTrue(fake.calls)
        for call in fake.calls:
            self.assertEqual(call[:4], ["fake-adb", "-s", NET, "shell"])
            self.assertEqual(len(call), 5, "one remote command string per call")

    def test_shell_quotes_argument_boundaries(self):
        fake = FakeAdb({})
        client = client_for(fake)
        client.shell(["pm", "list", "packages", "--user", "0", "a b;reboot"])
        self.assertEqual(fake.calls[-1][4], "pm list packages --user 0 'a b;reboot'")
        for argv in ([], ["getprop", "a\nb"], ["getprop", "a\x00"], ["/system/bin/sh", "-c"],
                     ["get prop"], "getprop ro.serialno", [1]):
            with self.subTest(argv=argv), self.assertRaises(ValueError):
                client.shell(argv)
        self.assertEqual(len(fake.calls), 1)

    def test_read_refuses_mutating_command_before_transport(self):
        fake = FakeAdb(api30())
        client = client_for(fake)
        for argv in (["pm", "disable-user", "--user", "0", "com.example.fakebloat"],
                     ["settings", "put", "--user", "0", "secure", "android_id", "x"],
                     ["am", "start", "-n", "com.example/.Factory"],
                     ["cmd", "package", "install", "x.apk"],
                     ["wm", "size", "800x480"],
                     ["getprop", "persist.sys.anything"]):
            with self.subTest(argv=argv), self.assertRaises(ValueError):
                client.read(argv)
        self.assertEqual(fake.calls, [])

    def test_default_runner_uses_bounded_timeout_without_host_shell(self):
        seen = {}

        def fake_run(args, **kwargs):
            seen["args"] = args
            seen.update(kwargs)
            return subprocess.CompletedProcess(args, 0, "23\n", "")

        client = AdbClient("fake-adb", USB, 0)
        with mock.patch.object(subprocess, "run", side_effect=fake_run):
            result = client.shell(["getprop", "ro.build.version.sdk"])
        self.assertEqual(result, CommandResult(0, "23\n", ""))
        self.assertEqual(seen["args"], ["fake-adb", "-s", USB, "shell",
                                        "getprop ro.build.version.sdk"])
        self.assertFalse(seen.get("shell", False))
        self.assertGreater(seen["timeout"], 0)
        self.assertLessEqual(seen["timeout"], 120)
        self.assertEqual(seen.get("stdin"), subprocess.DEVNULL)

        with mock.patch.object(subprocess, "run",
                               side_effect=subprocess.TimeoutExpired(["x"], 1)):
            with self.assertRaises(AdbError):
                client.shell(["getprop", "ro.build.version.sdk"])
        with mock.patch.object(subprocess, "run", side_effect=FileNotFoundError()):
            with self.assertRaises(AdbError):
                client.shell(["getprop", "ro.build.version.sdk"])


class DiscoveryTest(NoRealAdbTestCase):

    def test_sdk_uses_integer_sdk_not_release(self):
        fake = FakeAdb(api23())
        report = discover(client_for(fake))
        self.assertEqual(report.environment["sdk"], 23)
        self.assertIs(type(report.environment["sdk"]), int)
        self.assertNotIn("getprop ro.build.version.release", fake.remote_commands)
        for bad in ("6.0.1", "", "23abc", "-1", "0"):
            responses = api23()
            responses["getprop ro.build.version.sdk"] = {"stdout": bad + "\n"}
            with self.subTest(sdk=bad), self.assertRaises(DiscoveryError):
                discover(client_for(FakeAdb(responses)))

    def test_api23_falls_back_when_cmd_missing(self):
        missing_variants = {
            "legacy-exit-0": {"returncode": 0, "stdout": "/system/bin/sh: cmd: not found\n"},
            "exit-127": {"returncode": 127, "stderr": "/system/bin/sh: cmd: not found\n"},
        }
        for name, cmd_response in missing_variants.items():
            responses = api23()
            responses["cmd -l"] = cmd_response
            fake = FakeAdb(responses)
            with self.subTest(variant=name):
                report = discover(client_for(fake))
                self.assertEqual(report.environment["sdk"], 23)
                self.assertFalse(report.command_capabilities["cmd_package"])
                self.assertTrue(report.command_capabilities["disable_user"])
                self.assertFalse(report.command_capabilities["default_state"])
                self.assertEqual([c for c in fake.remote_commands if c.startswith("cmd ")],
                                 ["cmd -l"], "only the capability probe may use cmd")
                self.assertIn("pm list packages --user 0", fake.remote_commands)
                self.assertEqual(report.package_flags, {
                    "android": 0,
                    "com.android.launcher3": 0,
                    "com.autochips.bluetooth": 0,
                    "com.example.fakebloat": 3,
                    "com.example.fakeenabled": 1,
                })
                self.assertEqual(report.home_candidates, [
                    {"component": "com.android.launcher3/com.android.launcher3.Launcher",
                     "default": True}])
                self.assertIn("home.candidates", report.unresolved_facts)
                self.assertNotIn("home.default", report.unresolved_facts)
                self.assertEqual(fake.unscripted, [])

    def test_modern_cmd_package_reads_home_and_packages(self):
        fake = FakeAdb(api30())
        report = discover(client_for(fake))
        self.assertTrue(report.command_capabilities["cmd_package"])
        self.assertTrue(report.command_capabilities["default_state"])
        self.assertIn("cmd package list packages --user 0", fake.remote_commands)
        self.assertEqual(report.home_candidates, [
            {"component": "com.android.launcher3/.Launcher", "default": False},
            {"component": "com.example.fakehome/.HomeActivity", "default": True}])
        self.assertEqual(report.packages["com.example.fakehome"],
                         {"system": False, "version_code": 12})
        self.assertEqual(report.package_flags["com.example.fakebloat"], 3)
        self.assertEqual(report.display, {"width": 1280, "height": 480, "density": 240})
        self.assertEqual(report.unresolved_facts, [])

    def test_unknown_home_is_unresolved(self):
        cases = {
            "no-activity": {"stdout": "No activity found\n"},
            "garbage": {"stdout": "\x1b[0m??\n"},
            "failed": {"returncode": 1, "stderr": "Error: unknown command\n"},
            "not-a-candidate": {"stdout": "com.example.other/.Home\n"},
        }
        for name, response in cases.items():
            responses = api30()
            responses[HOME_RESOLVE] = response
            with self.subTest(case=name):
                report = discover(client_for(FakeAdb(responses)))
                self.assertIn("home.default", report.unresolved_facts)
                self.assertFalse(any(entry["default"] for entry in report.home_candidates))

        responses = api23()
        del responses["dumpsys package preferred-xml"]
        responses["dumpsys package preferred-xml"] = {"stdout": "<preferred-activities />\n"}
        report = discover(client_for(FakeAdb(responses)))
        self.assertIn("home.default", report.unresolved_facts)
        self.assertIn("home.candidates", report.unresolved_facts)
        self.assertEqual(report.home_candidates, [])

    def test_resolver_chooser_means_no_default_home(self):
        responses = api30()
        responses[HOME_RESOLVE] = {
            "stdout": "priority=0 preferredOrder=0 match=0x0 specificIndex=-1 isDefault=false\n"
                      "android/com.android.internal.app.ResolverActivity\n"}
        report = discover(client_for(FakeAdb(responses)))
        self.assertNotIn("home.default", report.unresolved_facts)
        self.assertFalse(any(entry["default"] for entry in report.home_candidates))

    def test_discovery_never_invokes_mutating_or_factory_commands(self):
        for fixture in (api23, api30):
            fake = FakeAdb(fixture())
            with self.subTest(fixture=fixture.__name__):
                discover(client_for(fake))
                self.assertEqual(fake.mutating_calls, [])
                self.assertEqual(fake.unscripted, [])
                self.assertEqual(fake.device_listing_calls, [])
                for remote in fake.remote_commands:
                    self.assertNotRegex(remote, r"^(am|monkey|input|svc|reboot|setprop)\b")
                    self.assertNotIn("install", remote)
                    self.assertNotIn("disable", remote)
                    self.assertNotIn("settings put", remote)

    def test_environment_matches_profile_contract(self):
        with open(REPO / "profiles" / "schema.json", encoding="utf-8") as handle:
            schema = json.load(handle)
        allowed_props = set(schema["properties"]["match"]["properties"]["buildProperties"]
                            ["properties"])
        platforms = schema["properties"]["platform"]["enum"]
        report = discover(client_for(FakeAdb(api23())))
        self.assertEqual(set(report.environment), {"platform", "sdk", "buildProperties"})
        self.assertIn(report.environment["platform"], platforms)
        self.assertEqual(report.environment["platform"], "aftermarket")
        self.assertEqual(set(report.environment["buildProperties"]), allowed_props)
        with open(REPO / "profiles" / "devices" / "xyauto-yt3518c" / "profile.json",
                  encoding="utf-8") as handle:
            profile = json.load(handle)
        for key, value in profile["match"]["buildProperties"].items():
            self.assertEqual(report.environment["buildProperties"][key], value)

    def test_blank_or_unknown_build_facts_are_unresolved(self):
        responses = api23()
        responses["getprop ro.product.name"] = {"stdout": "unknown\n"}
        responses["getprop ro.build.display.id"] = {"stdout": "  \n"}
        report = discover(client_for(FakeAdb(responses)))
        self.assertNotIn("product", report.environment["buildProperties"])
        self.assertNotIn("display", report.environment["buildProperties"])
        self.assertIn("buildProperties.product", report.unresolved_facts)
        self.assertIn("buildProperties.display", report.unresolved_facts)

    def test_automotive_feature_sets_aaos_platform(self):
        responses = api30()
        responses["pm list features"] = {
            "stdout": "feature:android.hardware.type.automotive\nfeature:android.hardware.wifi\n"}
        report = discover(client_for(FakeAdb(responses)))
        self.assertEqual(report.environment["platform"], "aaos")

    def test_unreadable_features_leave_platform_unresolved(self):
        responses = api30()
        responses["pm list features"] = {"returncode": 1, "stderr": "Error\n"}
        report = discover(client_for(FakeAdb(responses)))
        self.assertNotIn("platform", report.environment)
        self.assertIn("platform", report.unresolved_facts)
        self.assertTrue(report.read_only)

    def test_firmware_hash_matches_java_environment_key(self):
        report = discover(client_for(FakeAdb(api30())))
        self.assertEqual(report.identity.firmware_hash,
                         java_environment_key("aftermarket", 30,
                                              report.environment["buildProperties"]))

    def test_raw_property_value_kept_like_java_build(self):
        spaced = discover(client_for(FakeAdb(load_fixture(
            "discovery-api30-trailing-space.json"))))
        plain = discover(client_for(FakeAdb(api30())))
        props = spaced.environment["buildProperties"]
        self.assertEqual(props["display"], "FAKE_HU_11_V100 ")
        self.assertEqual(spaced.identity.firmware_hash,
                         java_environment_key("aftermarket", 30, props))
        self.assertNotEqual(spaced.identity.firmware_hash, plain.identity.firmware_hash)
        responses = api30()
        responses["getprop ro.build.display.id"] = {"stdout": "FAKE_HU_11_V100\r\n"}
        crlf = discover(client_for(FakeAdb(responses)))
        self.assertEqual(crlf.environment["buildProperties"]["display"], "FAKE_HU_11_V100")

    def test_non_ascii_digits_are_rejected_without_crash(self):
        responses = api30()
        responses["getprop ro.build.version.sdk"] = {"stdout": "2\u00b2\n"}
        with self.assertRaises(DiscoveryError):
            discover(client_for(FakeAdb(responses)))
        client = client_for(FakeAdb(api30()))
        with self.assertRaises(ValueError):
            client.read(["pm", "list", "packages", "--user", "\u00b2"])

    def test_missing_android_user_forces_read_only(self):
        for name, users in {
                "absent": {"stdout": "Users:\n\tUserInfo{10:Guest:10}\n"},
                "unreadable": {"returncode": 1, "stderr": "Error\n"}}.items():
            responses = api30()
            responses["pm list users"] = users
            with self.subTest(case=name):
                report = discover(client_for(FakeAdb(responses)))
                self.assertIsNotNone(report.identity)
                self.assertTrue(report.read_only)
                self.assertIn("android_user", report.unresolved_facts)
        self.assertFalse(discover(client_for(FakeAdb(api30()))).read_only)

    def test_user_name_cannot_forge_user_entry(self):
        responses = api30()
        responses["pm list users"] = {
            "stdout": "Users:\n\tUserInfo{0:x} UserInfo{7:fake:13} running\n"}
        report = discover(client_for(FakeAdb(responses), user=7))
        self.assertIn("android_user", report.unresolved_facts)
        self.assertTrue(report.read_only)

    def test_unresolved_packages_force_read_only(self):
        responses = api30()
        responses["cmd package list packages --user 0"] = {"returncode": 1, "stderr": "x\n"}
        report = discover(client_for(FakeAdb(responses)))
        self.assertIn("packages", report.unresolved_facts)
        self.assertTrue(report.read_only)

    def test_injected_package_header_is_ambiguous(self):
        responses = api30()
        dump = responses["dumpsys package packages"]["stdout"]
        marker = "    versionCode=12 minSdk=23 targetSdk=30\n"
        injected = (marker + "    versionName=1.0\n"
                    "  Package [com.android.launcher3] (feed01):\n"
                    "    User 0: installed=true hidden=false enabled=3\n")
        self.assertIn(marker, dump)
        responses["dumpsys package packages"] = {"stdout": dump.replace(marker, injected)}
        report = discover(client_for(FakeAdb(responses)))
        self.assertNotIn("com.android.launcher3", report.package_flags)
        self.assertIn("package_flags.com.android.launcher3", report.unresolved_facts)
        self.assertEqual(report.packages["com.android.launcher3"]["version_code"], None)
        self.assertIsNone(report.packages["com.android.launcher3"]["system"])
        self.assertIs(report.packages["com.example.fakebloat"]["system"], True)
        self.assertEqual(report.package_flags["com.example.fakebloat"], 3)


class IdentityTest(NoRealAdbTestCase):

    def test_hardware_serial_binding_ignores_transport_serial(self):
        over_usb = client_for(FakeAdb(api30()), serial=USB).identity()
        over_net = client_for(FakeAdb(api30()), serial=NET).identity()
        self.assertEqual(over_usb, over_net)
        self.assertEqual(len(over_usb.binding_hash), 64)
        self.assertEqual(over_usb.android_user, 0)

    def test_cloned_serial_with_distinct_android_ids_gets_distinct_binding(self):
        first = client_for(FakeAdb(api30())).identity()
        clone = api30()
        clone["settings get --user 0 secure android_id"] = {"stdout": "1b2a3f4e5d6c7b8a\n"}
        second = client_for(FakeAdb(clone)).identity()
        self.assertNotEqual(first.binding_hash, second.binding_hash)
        report = discover(client_for(FakeAdb(api30())))
        self.assertEqual(report.identity_source, "hardware-serial+android-id")
        serial_only = api30()
        serial_only["settings get --user 0 secure android_id"] = {"stdout": "null\n"}
        report = discover(client_for(FakeAdb(serial_only)))
        self.assertEqual(report.identity_source, "hardware-serial")
        self.assertFalse(report.read_only)

    def test_reassigned_ip_to_other_unit_changes_binding(self):
        first = client_for(FakeAdb(api30()), serial=NET).identity()
        other = api30()
        other["getprop ro.serialno"] = {"stdout": "FAKEHWSN00000099\n"}
        other["getprop ro.boot.serialno"] = {"stdout": "FAKEHWSN00000099\n"}
        second = client_for(FakeAdb(other), serial=NET).identity()
        self.assertNotEqual(first.binding_hash, second.binding_hash)
        self.assertEqual(first.firmware_hash, second.firmware_hash)

    def test_placeholder_serial_falls_back_to_user_scoped_android_id(self):
        first = discover(client_for(FakeAdb(api23())))
        self.assertEqual(first.identity_source, "android-id")
        self.assertFalse(first.read_only)
        other = api23()
        other["settings get --user 0 secure android_id"] = {"stdout": "1122334455667788\n"}
        second = discover(client_for(FakeAdb(other)))
        self.assertNotEqual(first.identity.binding_hash, second.identity.binding_hash)

        user10 = api23()
        for key in list(user10):
            if "--user 0" in key:
                user10[key.replace("--user 0", "--user 10")] = user10.pop(key)
        identity10 = client_for(FakeAdb(user10), user=10).identity()
        self.assertEqual(identity10.android_user, 10)
        self.assertNotEqual(identity10.binding_hash, first.identity.binding_hash)

    def test_unreliable_identity_marks_read_only(self):
        for android_id in ("null", "", "0000000000000000", "9774d56d682e549c", "not-hex!"):
            responses = api23()
            responses["settings get --user 0 secure android_id"] = {"stdout": android_id + "\n"}
            fake = FakeAdb(responses)
            with self.subTest(android_id=android_id):
                with self.assertRaises(IdentityUnavailable):
                    client_for(fake).identity()
                report = discover(client_for(fake))
                self.assertTrue(report.read_only)
                self.assertIsNone(report.identity)
                self.assertIn("target_identity", report.unresolved_facts)
                self.assertEqual(report.environment["sdk"], 23)

    def test_android_id_without_build_facts_is_read_only(self):
        responses = api23()
        for key in ("ro.product.board", "ro.product.manufacturer", "ro.product.name"):
            responses["getprop " + key] = {"stdout": "\n"}
        report = discover(client_for(FakeAdb(responses)))
        self.assertTrue(report.read_only)

    def test_report_excludes_raw_binding_inputs(self):
        raw_values = ("FAKEHWSN00000042", "8a7b6c5d4e3f2a1b", "0f1e2d3c4b5a6978", "192.0.2.10",
                      NET, USB)
        for fixture in (api23, api30):
            report = discover(client_for(FakeAdb(fixture()), serial=NET))
            exported = json.dumps(report.to_dict()) + repr(report) + repr(report.identity)
            for raw in raw_values:
                with self.subTest(fixture=fixture.__name__, raw=raw):
                    self.assertNotIn(raw, exported)
        responses = api23()
        responses["settings get --user 0 secure android_id"] = {"stdout": "null\n"}
        responses["getprop ro.serialno"] = {"stdout": "SHORT\n"}
        with self.assertRaises(IdentityUnavailable) as raised:
            client_for(FakeAdb(responses)).identity()
        self.assertNotIn("SHORT", str(raised.exception))


class CliTest(NoRealAdbTestCase):

    def setUp(self):
        super().setUp()
        SCRATCH.mkdir(exist_ok=True)
        self.workdir = Path(tempfile.mkdtemp(dir=SCRATCH))
        self.addCleanup(shutil.rmtree, self.workdir, True)

    def test_cli_discover_writes_private_report_and_lists_unresolved(self):
        fake = FakeAdb(api23())
        output = self.workdir / "private" / "report.json"
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            code = car.main(["discover", "--serial", NET, "--user", "0", "--output",
                             str(output), "--adb", "fake-adb"], runner=fake)
        self.assertEqual(code, 0)
        self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
        data = json.loads(output.read_text(encoding="utf-8"))
        self.assertEqual(data["environment"]["sdk"], 23)
        self.assertIn("home.candidates", data["unresolved_facts"])
        self.assertIn("unresolved: home.candidates", stdout.getvalue())
        self.assertEqual(fake.mutating_calls, [])
        self.assertTrue(all(call[1:3] == ["-s", NET] for call in fake.calls))

    def test_cli_reports_missing_adb_without_guessing(self):
        output = self.workdir / "report.json"
        with contextlib.redirect_stderr(io.StringIO()) as stderr:
            code = car.main(["discover", "--serial", NET, "--user", "0", "--output",
                             str(output)])
        self.assertNotEqual(code, 0)
        self.assertIn("adb", stderr.getvalue())
        self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
