"""Wrapper and CLI contracts: fake adb/Gradle only; nothing here contacts a device."""
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from io import StringIO
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import car  # noqa: E402
from car_support.adb import AdbClient  # noqa: E402
from car_support.profiles import discover, parse_profile  # noqa: E402
from fake_adb import FakeAdb, load_fixture  # noqa: E402

SCRATCH = Path(__file__).resolve().parent / "_scratch"
SCRIPTS = ("disable.sh", "restore.sh", "build-and-install.sh")
NET = "192.0.2.10:5555"
LIMIT = 524288

FAKE_ADB = '#!/bin/sh\necho "$@" >> "$FAKE_ADB_LOG"\nexit 1\n'
FAKE_GRADLEW = """#!/bin/sh
echo "$@" >> "$FAKE_GRADLE_LOG"
[ "${FAKE_GRADLE_FAIL:-0}" = "1" ] && exit 1
write() { mkdir -p "$(dirname "$1")"; head -c "$2" /dev/zero > "$1"; }
for task in "$@"; do
  case "$task" in
    :app:assembleRelease) write app/build/outputs/apk/release/app-release.apk "$FAKE_APK_BYTES" ;;
    :navbridge:assembleRelease) write addons/nav-bridge/build/outputs/apk/release/navbridge-release.apk 1000 ;;
  esac
done
"""
STUB_CAR = """import json, os, sys
with open(os.environ["FAKE_CAR_LOG"], "a") as handle:
    handle.write(json.dumps(sys.argv[1:]) + "\\n")
"""


def _executable(path, text):
    path.write_text(text, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)


class WrapperCase(unittest.TestCase):
    """Runs the real wrappers in a scratch tree with fake adb, fake Gradle and a stub car.py."""

    def setUp(self):
        SCRATCH.mkdir(exist_ok=True)
        self.root = Path(tempfile.mkdtemp(dir=str(SCRATCH)))
        self.addCleanup(shutil.rmtree, str(self.root), True)
        (self.root / "tools").mkdir()
        for name in SCRIPTS:
            shutil.copy(str(TOOLS / name), str(self.root / "tools" / name))
        (self.root / "tools" / "car.py").write_text(STUB_CAR, encoding="utf-8")
        _executable(self.root / "gradlew", FAKE_GRADLEW)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        _executable(self.bin / "adb", FAKE_ADB)
        self.adb_log = self.root / "adb.log"
        self.gradle_log = self.root / "gradle.log"
        self.car_log = self.root / "car.log"
        self.env = dict(
            os.environ, FAKE_ADB_LOG=str(self.adb_log), FAKE_GRADLE_LOG=str(self.gradle_log),
            FAKE_CAR_LOG=str(self.car_log), ADB=str(self.bin / "adb"),
            PATH=os.pathsep.join([str(self.bin), str(Path(sys.executable).parent),
                                  "/usr/bin", "/bin"]),
            FAKE_APK_BYTES="1000")
        for name in ("BUILD_ONLY", "CAR_HOST", "WITH_BRIDGE", "DISABLE_STOCK_LAUNCHER"):
            self.env.pop(name, None)

    def run_script(self, name, *args, scripts_dir=None, **extra):
        env = dict(self.env, **extra)
        directory = scripts_dir or (self.root / "tools")
        return subprocess.run(["bash", str(directory / name)] + list(args), cwd=str(self.root),
                              env=env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                              stderr=subprocess.STDOUT, universal_newlines=True, timeout=60)

    def log(self, path):
        return path.read_text(encoding="utf-8").splitlines() if path.exists() else []

    def car_calls(self):
        return [json.loads(line) for line in self.log(self.car_log)]

    def assertNoAdb(self):
        self.assertEqual(self.log(self.adb_log), [])


TARGET = ["--serial", "FAKE-USB-0001", "--user", "0"]
INSTALL_ARGS = TARGET + ["--profile", "p.json", "--output", "out/plan.json"]


class WrapperTest(WrapperCase):

    def test_wrappers_never_autoconfirm(self):
        for name, args in (
                ("disable.sh", ["list.txt"] + TARGET + ["--confirm", "abc"]),
                ("restore.sh", ["op-1"] + TARGET + ["--confirm", "abc"]),
                ("build-and-install.sh", INSTALL_ARGS + ["--confirm", "abc"]),
                ("disable.sh", ["list.txt", "--yes"]),
                ("restore.sh", ["op-1", "--apply"])):
            with self.subTest(name=name, args=args):
                result = self.run_script(name, *args)
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn("confirm", result.stdout.lower())
        (self.root / "list.txt").write_text("com.example.app\n", encoding="utf-8")
        calls = []
        for name, args in (
                ("disable.sh", ["list.txt"] + TARGET + ["--profile", "p.json", "--policy",
                                                        "q.json", "--output", "o.json"]),
                ("restore.sh", ["op-1"] + TARGET + ["--output", "o.json"]),
                ("build-and-install.sh", INSTALL_ARGS)):
            result = self.run_script(name, *args)
            self.assertEqual(result.returncode, 0, result.stdout)
        calls = self.car_calls()
        self.assertEqual([c[0] for c in calls],
                         ["plan-debloat", "plan-restore", "apk-metadata", "plan-install"])
        for call in calls:
            self.assertNotIn("apply", call)
            self.assertNotIn("--confirm", call)
        self.assertNoAdb()
        for name in SCRIPTS:
            text = (TOOLS / name).read_text(encoding="utf-8")
            code = "\n".join(line for line in text.splitlines()
                            if not line.lstrip().startswith(("#", "echo")))
            self.assertNotRegex(code, r"car\.py\"?\s+apply")
            self.assertNotRegex(code, r"(?<![-\w])(adb|ADB_BIN|ADB)\b")

    def test_wrappers_have_no_legacy_side_effects(self):
        for name in SCRIPTS:
            text = (TOOLS / name).read_text(encoding="utf-8")
            for forbidden in ("PROTECTED_PACKAGES", "appops", "pm grant", "pm enable",
                              "pm disable", "accessibility", "startservice", "am start",
                              "launcher3", "DISABLE_STOCK_LAUNCHER", "CAR_HOST=", "HOME/",
                              "settings put"):
                with self.subTest(script=name, forbidden=forbidden):
                    self.assertNotIn(forbidden, text)

    def test_build_only_invokes_no_adb(self):
        for size, ok in ((LIMIT - 1, True), (LIMIT, False), (LIMIT + 1, False)):
            with self.subTest(size=size):
                self.gradle_log.unlink() if self.gradle_log.exists() else None
                result = self.run_script("build-and-install.sh", BUILD_ONLY="1",
                                         FAKE_APK_BYTES=str(size))
                self.assertEqual(result.returncode == 0, ok, result.stdout)
                if not ok:
                    self.assertIn("size gate", result.stdout)
                self.assertEqual(self.log(self.gradle_log), [":app:assembleRelease"])
                self.assertNoAdb()
                self.assertEqual(self.car_calls(), [])

    def test_build_failure_never_uses_a_stale_apk(self):
        self.run_script("build-and-install.sh", BUILD_ONLY="1", FAKE_APK_BYTES="1000")
        result = self.run_script("build-and-install.sh", BUILD_ONLY="1", FAKE_GRADLE_FAIL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("APK size", result.stdout)

    def test_size_gate_blocks_install_planning(self):
        result = self.run_script("build-and-install.sh", *INSTALL_ARGS,
                                 FAKE_APK_BYTES=str(LIMIT))
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.car_calls(), [])
        self.assertNoAdb()

    def test_bridge_not_installed_by_default(self):
        result = self.run_script("build-and-install.sh", *INSTALL_ARGS)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.log(self.gradle_log), [":app:assembleRelease"])
        plans = [c for c in self.car_calls() if c[0] == "plan-install"]
        self.assertEqual(len(plans), 1)
        self.assertNotIn("navbridge", json.dumps(self.car_calls()))
        self.assertNoAdb()

        self.gradle_log.unlink()
        self.car_log.unlink()
        result = self.run_script("build-and-install.sh", *INSTALL_ARGS, "--with-bridge")
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(self.log(self.gradle_log),
                         [":app:assembleRelease :navbridge:assembleRelease"])
        calls = self.car_calls()
        self.assertEqual([c[0] for c in calls],
                         ["apk-metadata", "plan-install", "apk-metadata", "plan-install"])
        self.assertTrue(all(c[0] != "apply" for c in calls))
        self.assertNoAdb()
        self.assertIn("car.py apply", result.stdout)

    def test_old_list_restore_usage_refused(self):
        (self.root / "debloat.txt").write_text("com.example.app\n", encoding="utf-8")
        for args in (["debloat.txt"], ["debloat.txt"] + TARGET + ["--output", "o.json"],
                     ["examples/x/debloat.txt"] + TARGET, ["../op"] + TARGET,
                     ["op id"] + TARGET, []):
            with self.subTest(args=args):
                result = self.run_script("restore.sh", *args)
                self.assertEqual(result.returncode, 2, result.stdout)
        self.assertEqual(self.car_calls(), [])
        self.assertNoAdb()
        # Without the stub, the shipped wrapper plus real CLI also refuse the old usage.
        result = self.run_script("restore.sh", "debloat.txt", scripts_dir=TOOLS)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertNoAdb()

    def test_old_disable_usage_and_host_env_refused(self):
        (self.root / "debloat.txt").write_text("com.example.app\n", encoding="utf-8")
        for name, args in (("disable.sh", ["debloat.txt"]), ("restore.sh", ["op-1"]),
                           ("build-and-install.sh", INSTALL_ARGS)):
            with self.subTest(name=name):
                result = self.run_script(name, *args, CAR_HOST="192.0.2.10")
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertIn("CAR_HOST", result.stdout)
        result = self.run_script("disable.sh", "debloat.txt", scripts_dir=TOOLS)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertNoAdb()
        self.assertEqual(self.car_calls(), [])

    def test_build_wrapper_requires_target_before_building(self):
        for args in ([], ["--serial", "S", "--user", "0"],
                     ["--serial", "S", "--profile", "p.json", "--output", "o.json"],
                     ["--user", "0", "--profile", "p.json", "--output", "o.json"],
                     ["--serial", "S", "--user", "x", "--profile", "p", "--output", "o"]):
            with self.subTest(args=args):
                result = self.run_script("build-and-install.sh", *args)
                self.assertEqual(result.returncode, 2, result.stdout)
        self.assertEqual(self.log(self.gradle_log), [])
        self.assertNoAdb()

    def test_dry_run_is_static_only(self):
        good = self.root / "good.txt"
        good.write_text("com.example.app\n", encoding="utf-8")
        result = self.run_script("disable.sh", str(good), "--dry-run", scripts_dir=TOOLS)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("NOT a device safety or qualification result", result.stdout)
        bad = self.root / "bad.txt"
        bad.write_text("com.example.app\ncom.android.systemui # note\n", encoding="utf-8")
        result = self.run_script("disable.sh", str(bad), "--dry-run", scripts_dir=TOOLS)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        result = self.run_script("disable.sh", str(good), "--dry-run", *TARGET,
                                 scripts_dir=TOOLS)
        self.assertEqual(result.returncode, 2, result.stdout)
        self.assertNoAdb()


class CliContractTest(unittest.TestCase):

    def run_main(self, argv):
        runner = FakeAdb(load_fixture("discovery-api30-aftermarket.json"))
        out, err = StringIO(), StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            try:
                code = car.main(argv, runner=runner)
            except SystemExit as exit_:
                code = exit_.code
        return code, runner, err.getvalue()

    def test_cli_rejects_missing_serial_user_or_profile(self):
        full = {
            "discover": ["--serial", NET, "--user", "0", "--output", "o.json"],
            "plan-debloat": ["--serial", NET, "--user", "0", "--output", "o.json",
                             "--profile", "p.json", "--policy", "q.json",
                             "--candidates", "c.txt"],
            "plan-install": ["--serial", NET, "--user", "0", "--output", "o.json",
                             "--profile", "p.json", "--apk-metadata", "m.json"],
            "plan-restore": ["--serial", NET, "--user", "0", "--output", "o.json",
                             "--operation-id", "op-1"],
            "apply": ["--serial", NET, "--user", "0", "--plan", "p.json", "--confirm", "x"],
        }
        for command, args in full.items():
            required = {"--serial", "--user"}
            if command in {"plan-debloat", "plan-install"}:
                required.add("--profile")
            for flag in sorted(required):
                trimmed = list(args)
                index = trimmed.index(flag)
                del trimmed[index:index + 2]
                with self.subTest(command=command, missing=flag):
                    code, runner, err = self.run_main([command] + trimmed)
                    self.assertEqual(code, 2, err)
                    self.assertEqual(runner.calls, [])
        code, runner, _ = self.run_main(["apply", "--serial", NET, "--user", "0",
                                         "--plan", "p.json"])
        self.assertEqual(code, 2)
        self.assertEqual(runner.calls, [])

    def private_report(self):
        fake = FakeAdb(load_fixture("discovery-api30-aftermarket.json"))
        report = discover(AdbClient("fake-adb", NET, 0, runner=fake)).to_dict()
        return report

    def export(self, report, directory):
        source = directory / "report.json"
        source.write_text(json.dumps(report), encoding="utf-8")
        output = directory / "out" / "candidate.json"
        code, runner, err = self.run_main(["export-profile", "--report", str(source),
                                           "--output", str(output)])
        self.assertEqual(runner.calls, [])
        return code, output, err

    def test_export_never_promotes_report_to_verified(self):
        SCRATCH.mkdir(exist_ok=True)
        directory = Path(tempfile.mkdtemp(dir=str(SCRATCH)))
        self.addCleanup(shutil.rmtree, str(directory), True)
        report = self.private_report()
        self.assertIsNotNone(report["identity"])
        report["classification"] = "maintainer-reviewed"
        report["environment"]["classification"] = "device-tested"
        report["evidence"] = {"classification": "maintainer-reviewed"}
        report["serial"] = "SECRETSERIAL123"
        report["accounts"] = ["person@example.invalid"]
        code, output, err = self.export(report, directory)
        self.assertEqual(code, 0, err)
        text = output.read_text(encoding="utf-8")
        exported = json.loads(text)
        self.assertEqual(exported["evidence"]["classification"], "reported")
        parse_profile(text.encode("utf-8"), 1)
        self.assertEqual(exported["roleDefaults"], {})
        self.assertEqual(exported["protectedPackages"], [])
        self.assertEqual(exported["match"]["sdkMin"], report["environment"]["sdk"])
        self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o600)
        leaks = [report["identity"]["binding_hash"], report["identity"]["firmware_hash"],
                 "SECRETSERIAL123", "person@example.invalid", "192.0.2.10", "FAKE-USB-0001",
                 "binding_hash", "identity"]
        leaks.extend(name for name in report["packages"] if "." in name)
        for leak in leaks:
            with self.subTest(leak=leak):
                self.assertNotIn(leak, text)

    def test_export_malformed_report_types_refused_without_traceback(self):
        SCRATCH.mkdir(exist_ok=True)
        directory = Path(tempfile.mkdtemp(dir=str(SCRATCH)))
        self.addCleanup(shutil.rmtree, str(directory), True)
        cases = [("platform", v) for v in ([], {}, ["aftermarket"], {"a": 1})]
        cases += [("buildProperties", v) for v in ([], ["board"], "board", 7, None)]
        cases += [("environment", v) for v in ([], "x", None)]
        cases += [("sdk", v) for v in ([], {}, "30", True)]
        for field, value in cases:
            report = self.private_report()
            if field == "environment":
                report["environment"] = value
            elif field == "buildProperties":
                report["environment"]["buildProperties"] = value
            else:
                report["environment"][field] = value
            with self.subTest(field=field, value=value):
                code, output, err = self.export(report, directory)
                if field == "platform":
                    # An unrecognized platform value is not trusted; the candidate is still valid.
                    self.assertEqual(code, 0, err)
                    self.assertEqual(json.loads(output.read_text())["platform"], "aftermarket")
                    output.unlink()
                else:
                    self.assertEqual(code, 1, err)
                    self.assertNotIn("Traceback", err)
                    self.assertFalse(output.exists())
        source = directory / "list.json"
        source.write_text("[1, 2]", encoding="utf-8")
        code, _, err = self.run_main(["export-profile", "--report", str(source),
                                      "--output", str(directory / "x.json")])
        self.assertEqual(code, 1)
        self.assertFalse((directory / "x.json").exists())

    def test_export_refuses_unusable_reports(self):
        SCRATCH.mkdir(exist_ok=True)
        directory = Path(tempfile.mkdtemp(dir=str(SCRATCH)))
        self.addCleanup(shutil.rmtree, str(directory), True)
        for mutate in (lambda r: r["environment"].update(buildProperties={}),
                       lambda r: r["environment"].update(sdk=None),
                       lambda r: r.update(reportVersion=2)):
            report = self.private_report()
            mutate(report)
            code, output, err = self.export(report, directory)
            self.assertEqual(code, 1, err)
            self.assertFalse(output.exists())
        code, runner, err = self.run_main(["export-profile", "--report", str(directory / "no"),
                                           "--output", str(directory / "o.json")])
        self.assertEqual(code, 1)


if __name__ == "__main__":
    unittest.main()
