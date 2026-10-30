import copy
import json
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parents[1]
REPO = TOOLS.parent
sys.path.insert(0, str(TOOLS))

from car_support.adb import AdbClient, environment_key  # noqa: E402
from car_support.profiles import (PROTECTED_PACKAGES, PolicyError,  # noqa: E402
                                  ProfileFormatError, candidate_list_hash, discover,
                                  load_policy, load_profile, parse_profile, profile_hash,
                                  sanitize, validate_candidates, validate_debloat_policy)
from fake_adb import FakeAdb, load_fixture  # noqa: E402

FIXTURES = REPO / "profiles" / "fixtures"
XYAUTO_PROFILE = REPO / "profiles" / "devices" / "xyauto-yt3518c" / "profile.json"
XYAUTO_LIST = REPO / "examples" / "yt3518c-mt3561s" / "debloat.txt"
XYAUTO_POLICY = REPO / "examples" / "yt3518c-mt3561s" / "debloat-policy.json"
JAVA_SAFETY_TEST = (REPO / "app" / "src" / "test" / "java" / "com" / "cleanyourcar"
                    / "launcher" / "DebloatSafetyTest.java")
DISABLE_SCRIPT = TOOLS / "disable.sh"
NET = "192.0.2.10:5555"


def fixture_text(name):
    return (FIXTURES / name).read_text(encoding="utf-8")


def fixture_object(name):
    return json.loads(fixture_text(name))


def reason_of(document, app_version_code=1):
    data = document if isinstance(document, (bytes, str)) else json.dumps(document)
    if isinstance(data, str):
        data = data.encode("utf-8")
    try:
        parse_profile(data, app_version_code)
    except ProfileFormatError as error:
        return error.reason
    raise AssertionError("profile unexpectedly accepted")


def discovered(fixture, fake=None):
    fake = fake or FakeAdb(load_fixture(fixture))
    return discover(AdbClient("fake-adb", NET, 0, runner=fake)), fake


def api30_profile():
    return {
        "schemaVersion": 1,
        "profileId": "fake-hu-11",
        "revision": 1,
        "minAppVersionCode": 1,
        "platform": "aftermarket",
        "adapterId": "generic",
        "match": {"sdkMin": 30, "sdkMax": 30, "buildProperties": {
            "board": "fakeboard_v2", "manufacturer": "FakeVendor", "display": "FAKE_HU_11_V100",
            "product": "fakeproduct"}},
        "roleDefaults": {"navigation": {"packageName": "com.google.android.apps.maps",
                                        "strategyId": "geo"}},
        "featureClaims": {},
        "protectedPackages": ["com.example.vehicle.can"],
        "evidence": {"classification": "maintainer-reviewed"},
    }


def qualified_case():
    """A report/profile/policy/candidates set that passes every gate (positive control)."""
    report, fake = discovered("discovery-api30-aftermarket.json")
    profile = parse_profile(json.dumps(api30_profile()).encode("utf-8"), 1)
    candidates = ["com.example.fakebloat"]
    policy = {
        "policyVersion": 1,
        "profileId": "fake-hu-11",
        "profileHash": profile_hash(profile),
        "firmwareHash": environment_key(report.environment),
        "candidateList": "debloat.txt",
        "candidateListHash": candidate_list_hash(candidates),
        "status": "qualified",
        "protectedComponents": ["com.example.vehicle.mcu/.McuService"],
        "unresolvedDependencies": [],
        "qualification": {"reviewers": ["maintainer:fake"],
                          "evidence": ["docs/devices/fake-hu-11.md#debloat"]},
    }
    return report, profile, policy, candidates, fake


class NoProcessTestCase(unittest.TestCase):

    def setUp(self):
        for name in ("run", "Popen", "call", "check_call", "check_output"):
            patcher = mock.patch.object(subprocess, name,
                                        side_effect=AssertionError("real process launched"))
            patcher.start()
            self.addCleanup(patcher.stop)
        env = mock.patch.dict(os.environ, {"PATH": ""})
        env.start()
        self.addCleanup(env.stop)


class ProfileContractTest(NoProcessTestCase):

    def test_shared_profile_fixtures_match_java_contract(self):
        # Same outcomes as ProfileCodecTest for the shared fixture files.
        xyauto = load_profile(FIXTURES / "valid-xyauto.json", 1)
        self.assertEqual(xyauto["profileId"], "xyauto-yt3518c")
        self.assertEqual(xyauto["match"]["sdkMin"], 23)
        self.assertNotIn("fingerprint", xyauto["match"]["buildProperties"])
        self.assertIn("com.autochips.android.backcar", xyauto["protectedPackages"])
        generic = load_profile(FIXTURES / "valid-generic.json", 1)
        self.assertEqual(generic["adapterId"], "generic")
        self.assertEqual(load_profile(XYAUTO_PROFILE, 1), xyauto)
        for name, reason in (("invalid-command.json", "unknown_field"),
                             ("invalid-adapter.json", "unknown_adapter"),
                             ("invalid-type.json", "bad_type")):
            with self.subTest(fixture=name), self.assertRaises(ProfileFormatError) as raised:
                load_profile(FIXTURES / name, 1)
            self.assertEqual(raised.exception.reason, reason)
            self.assertIsInstance(raised.exception, ValueError)

        # Mutations ported one-for-one from ProfileCodecTest with the same reason codes.
        cases = []

        def mutate(name, reason, change):
            document = fixture_object(name)
            change(document)
            cases.append((reason, document))

        roles = lambda d: d["roleDefaults"]  # noqa: E731
        mutate("valid-generic.json", "unknown_strategy",
               lambda d: roles(d)["navigation"].update(strategyId="shell-exec"))
        mutate("valid-generic.json", "invalid_value", lambda d: roles(d).update(
            radio={"packageName": "com.example.radio", "strategyId": "geo"}))
        mutate("valid-generic.json", "invalid_value", lambda d: roles(d).update(
            equalizer={"packageName": "com.xyauto.Settings", "strategyId": "xyauto-equalizer"}))
        mutate("valid-xyauto.json", "invalid_value",
               lambda d: roles(d)["radio"].update(componentName="com.android.shell/.Exec"))
        mutate("valid-generic.json", "invalid_value", lambda d: roles(d).update(
            settings={"packageName": "com.android.settings", "strategyId": "launch-component"}))
        mutate("valid-generic.json", "unknown_field", lambda d: roles(d).update(
            broadcast={"packageName": "com.example", "strategyId": "launch-package"}))
        mutate("valid-generic.json", "unknown_field",
               lambda d: d["evidence"].update(grantPermissions=["x"]))
        mutate("valid-generic.json", "unsupported_schema", lambda d: d.update(schemaVersion=2))
        mutate("valid-generic.json", "unsupported_schema", lambda d: d.update(schemaVersion=0))
        mutate("valid-generic.json", "bad_type", lambda d: d.update(schemaVersion="1"))
        mutate("valid-generic.json", "bad_type", lambda d: d.update(platform=None))
        mutate("valid-generic.json", "missing_field", lambda d: d.pop("evidence"))
        mutate("valid-generic.json", "invalid_value", lambda d: d.update(platform="desktop"))
        mutate("valid-xyauto.json", "invalid_value",
               lambda d: d["match"]["buildProperties"].update(board=""))
        mutate("valid-xyauto.json", "unknown_field",
               lambda d: d["match"]["buildProperties"].update({"ro.secure": "0"}))
        mutate("valid-generic.json", "invalid_value",
               lambda d: d["featureClaims"].update(overlay="verified"))
        mutate("valid-generic.json", "invalid_value", lambda d: d.update(revision=0))
        mutate("valid-generic.json", "invalid_value",
               lambda d: d.update(protectedPackages=["rm -rf /"]))
        mutate("valid-generic.json", "invalid_value",
               lambda d: d.update(protectedPackages=["com.a.b", "com.a.b"]))
        mutate("valid-generic.json", "invalid_value",
               lambda d: d["evidence"].update(notes="\u00e9" * 2049))
        mutate("valid-generic.json", "invalid_value", lambda d: d["match"].update(sdkMax=22))
        mutate("valid-generic.json", "bad_type", lambda d: d.update(revision=True))
        mutate("valid-generic.json", "bad_type", lambda d: d.update(revision=2147483648))
        mutate("valid-generic.json", "too_many_packages", lambda d: d.update(
            protectedPackages=["com.example.p%d" % i for i in range(257)]))
        for reason, document in cases:
            with self.subTest(reason=reason, document=json.dumps(document)[:120]):
                self.assertEqual(reason_of(document), reason)

        decimal = fixture_text("valid-generic.json").replace('"schemaVersion": 1,',
                                                             '"schemaVersion": 1.0,')
        self.assertIn("1.0", decimal)
        self.assertEqual(reason_of(decimal), "bad_type")
        self.assertEqual(reason_of('{"schemaVersion": 1'), "malformed")
        self.assertEqual(reason_of(fixture_text("valid-generic.json") + "{}"), "malformed")
        self.assertEqual(reason_of("[]"), "bad_type")

        max_packages = fixture_object("valid-generic.json")
        max_packages["protectedPackages"] = ["com.example.p%d" % i for i in range(256)]
        self.assertEqual(len(parse_profile(json.dumps(max_packages).encode(), 1)
                             ["protectedPackages"]), 256)

        base = fixture_text("valid-generic.json").encode("utf-8")
        at_limit = base + b" " * (65536 - len(base))
        self.assertEqual(parse_profile(at_limit, 1)["schemaVersion"], 1)
        self.assertEqual(reason_of(at_limit + b" "), "too_large")
        multibyte = fixture_object("valid-generic.json")
        multibyte["evidence"]["notes"] = ""
        template = len(json.dumps(multibyte, ensure_ascii=False).encode("utf-8"))
        remaining = 65537 - template
        multibyte["evidence"]["notes"] = "\u00e9" * (remaining // 2) + "a" * (remaining % 2)
        oversized = json.dumps(multibyte, ensure_ascii=False)
        self.assertEqual(len(oversized.encode("utf-8")), 65537)
        self.assertLess(len(oversized), 65536)
        self.assertEqual(reason_of(oversized), "too_large")

        too_old = fixture_object("valid-generic.json")
        too_old["minAppVersionCode"] = 5
        self.assertEqual(reason_of(too_old, 4), "app_too_old")
        self.assertEqual(parse_profile(json.dumps(too_old).encode(), 5)["minAppVersionCode"], 5)

        # Tooling is never more lenient than the app: non-standard JSON is malformed.
        generic_text = fixture_text("valid-generic.json")
        for text in ("\ufeff" + generic_text,
                     generic_text.replace('"revision": 1,', '"revision": 1, "revision": 1,'),
                     generic_text.replace('"revision": 1,', '"revision": NaN,'),
                     generic_text.replace('"revision": 1,', "'revision': 1,"),
                     generic_text.replace('"revision": 1,', '"revision": 1, // c\n')):
            with self.subTest(text=text[:40]):
                self.assertEqual(reason_of(text), "malformed")
        self.assertEqual(reason_of(b"\xff\xfe{}"), "malformed")


class CandidateListTest(NoProcessTestCase):

    def test_bom_crlf_comments_normalize_before_validation(self):
        lines = ["\ufeff# header comment\r\n", "com.example.one\r\n",
                 "  com.example.two\t# inline note\r\n", "\r\n", "   \n", "# trailing",
                 "com.example.three"]
        self.assertEqual(validate_candidates(lines),
                         ["com.example.one", "com.example.two", "com.example.three"])
        self.assertEqual(validate_candidates(["\ufeffcom.example.one"]), ["com.example.one"])
        # A BOM is only a file marker; anywhere else it is not package syntax.
        with self.assertRaises(PolicyError):
            validate_candidates(["com.example.one", "\ufeffcom.example.two"])
        # The protected-package check runs on the normalized name.
        for line in ("com.autochips.android.backcar # inline comment attempt\r\n",
                     "\ufeffcom.xygala.canbus\r\n", "  com.android.systemui  "):
            with self.subTest(line=line), self.assertRaises(PolicyError):
                validate_candidates(["com.example.one", line])
        historical = validate_candidates(
            XYAUTO_LIST.read_text(encoding="utf-8").splitlines(keepends=True))
        self.assertGreaterEqual(len(historical), 15)
        self.assertEqual(len(historical), len(set(historical)))
        self.assertEqual(candidate_list_hash(historical),
                         candidate_list_hash(validate_candidates(
                             [line.rstrip("\n") + "\r\n" for line in
                              XYAUTO_LIST.read_text(encoding="utf-8").splitlines()])))
        self.assertNotEqual(candidate_list_hash(historical),
                            candidate_list_hash(historical[:-1]))

    def test_shell_metacharacters_or_empty_list_refused(self):
        with self.assertRaises(PolicyError):
            validate_candidates(["com.example.app; reboot"])
        for line in ("com.example.app && reboot", "$(reboot)", "`reboot`", "com.example.app|sh",
                     "com.example.app>x", "com.example.app\x00", "com.example.app\nreboot",
                     "-f", "--user", "com.example.app reboot", "com..example", "1com.example",
                     "com.example.", ".com.example", "com/example", "com.example.app'",
                     'com.example.app"', "com.example.\u0430pp", "singlesegment",
                     "com.example." + "a" * 120, "com.example.app\\", "*", "com.example.*"):
            with self.subTest(line=line), self.assertRaises(PolicyError):
                validate_candidates([line])
        for lines in ([], ["# only a comment", "", "   ", "\r\n"], ["\ufeff"]):
            with self.subTest(lines=lines), self.assertRaises(PolicyError):
                validate_candidates(lines)
        for lines in (["com.example.a", "com.example.a"],
                      ["com.example.a", "com.example.a # again"],
                      ["com.example.p%d" % i for i in range(257)],
                      ["com.example.a", 7], "com.example.a"):
            with self.subTest(lines=str(lines)[:40]), self.assertRaises(PolicyError):
                validate_candidates(lines)
        self.assertEqual(len(validate_candidates(["com.example.p%d" % i for i in range(256)])),
                         256)

    def test_core_and_vehicle_packages_unconditionally_protected(self):
        required = [
            "android", "com.android.systemui", "com.android.settings", "com.android.phone",
            "com.android.server.telecom", "com.android.shell", "com.android.bluetooth",
            "com.android.launcher3", "com.cleanyourcar.launcher", "com.cleanyourcar.navbridge",
            # permission controllers and package installers
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            # ext services/shared and module metadata
            "com.android.ext.services", "com.google.android.ext.services",
            "com.android.ext.shared", "com.google.android.ext.shared",
            "com.android.modulemetadata", "com.google.android.modulemetadata",
            # core providers
            "com.android.providers.settings", "com.android.providers.media",
            "com.android.providers.downloads", "com.android.providers.telephony",
            "com.android.providers.contacts",
            # firmware-specific vehicle packages (XYAuto reference)
            "com.autochips.android.backcar", "com.autochips.bluetooth", "com.xygala.canbus",
            "com.xygala.pvcanset", "com.xy.spl", "com.xy.brakecheck", "com.xyauto.Settings",
            "com.fvsm.camera", "com.acloud.stub.localmusic", "com.acloud.stub.localradio",
            "com.mediatek.usbchecker",
        ]
        for package in required:
            with self.subTest(package=package):
                self.assertIn(package, PROTECTED_PACKAGES)
                with self.assertRaises(PolicyError):
                    validate_candidates(["com.example.fakebloat", package])

        # Every protection in the existing Java safety test, shell guard, and the shipped
        # XYAuto profile stays protected here: one set is never weaker than another.
        java = JAVA_SAFETY_TEST.read_text(encoding="utf-8")
        java_block = re.search(r"PROTECTED_PACKAGES = new HashSet<>\(Arrays\.asList\((.*?)\)\)",
                               java, re.S).group(1)
        shell = DISABLE_SCRIPT.read_text(encoding="utf-8")
        shell_block = re.search(r"PROTECTED_PACKAGES=\((.*?)\n\)", shell, re.S).group(1)
        for source, block in (("DebloatSafetyTest", java_block), ("disable.sh", shell_block)):
            names = re.findall(r'"([^"]+)"', block)
            self.assertGreater(len(names), 20, source)
            with self.subTest(source=source):
                self.assertEqual(sorted(set(names) - PROTECTED_PACKAGES), [])
        profile = load_profile(XYAUTO_PROFILE, 1)
        self.assertEqual(sorted(set(profile["protectedPackages"]) - PROTECTED_PACKAGES), [])
        roles = {r["packageName"] for r in profile["roleDefaults"].values()}
        self.assertEqual(sorted(roles - PROTECTED_PACKAGES), [])

        # A profile- or policy-declared vehicle package is refused even when it is in the
        # reviewed candidate list, and so is a role default; the policy cannot unprotect it.
        report, profile, policy, candidates, fake = qualified_case()
        validate_debloat_policy(report, profile, policy, candidates)
        for extra in ("com.example.vehicle.can", "com.example.vehicle.mcu",
                      "com.google.android.apps.maps"):
            changed_report = copy.deepcopy(report)
            changed_report.packages[extra] = {"system": True, "version_code": 1}
            changed_report.package_flags[extra] = 0
            changed = candidates + [extra]
            changed_policy = dict(policy, candidateListHash=candidate_list_hash(changed))
            with self.subTest(extra=extra), self.assertRaises(PolicyError):
                validate_debloat_policy(changed_report, profile, changed_policy, changed)
        self.assertEqual(fake.mutating_calls, [])

    def test_unknown_dependency_or_home_refused(self):
        report, profile, policy, candidates, fake = qualified_case()
        validate_debloat_policy(report, profile, policy, candidates)

        def refused(report=report, policy=policy, candidates=candidates):
            with self.assertRaises(PolicyError):
                validate_debloat_policy(report, profile, policy, candidates)

        with self.subTest("unresolved dependency recorded for a candidate"):
            refused(policy=dict(policy, unresolvedDependencies=[
                {"package": "com.example.fakebloat", "reason": "camera overlay unknown"}]))
        with self.subTest("any unresolved dependency blocks a qualified policy"):
            refused(policy=dict(policy, unresolvedDependencies=[
                {"package": "com.example.other", "reason": "not reviewed"}]))
        with self.subTest("candidate absent from the device"):
            changed = candidates + ["com.example.notinstalled"]
            refused(policy=dict(policy, candidateListHash=candidate_list_hash(changed)),
                    candidates=changed)
        with self.subTest("system flag unresolved"):
            changed = copy.deepcopy(report)
            changed.packages["com.example.fakebloat"]["system"] = None
            refused(report=changed)
        with self.subTest("exact original flag unresolved"):
            changed = copy.deepcopy(report)
            del changed.package_flags["com.example.fakebloat"]
            changed.unresolved_facts.append("package_flags.com.example.fakebloat")
            refused(report=changed)
        with self.subTest("metadata unresolved"):
            changed = copy.deepcopy(report)
            changed.unresolved_facts.append("packages.com.example.fakebloat.metadata")
            refused(report=changed)
        with self.subTest("candidate is the default HOME"):
            changed = copy.deepcopy(report)
            changed.packages["com.example.fakehome"]["system"] = True
            refused(report=changed, candidates=["com.example.fakehome"],
                    policy=dict(policy, candidateListHash=candidate_list_hash(
                        ["com.example.fakehome"])))
        with self.subTest("candidate provides a non-default HOME"):
            changed = copy.deepcopy(report)
            changed.home_candidates.append(
                {"component": "com.example.fakebloat/.AltHome", "default": False})
            refused(report=changed)
        with self.subTest("default HOME unresolved"):
            changed = copy.deepcopy(report)
            changed.unresolved_facts.append("home.default")
            refused(report=changed)
        with self.subTest("HOME candidates and default both unknown"):
            changed = copy.deepcopy(report)
            changed.home_candidates = []
            changed.unresolved_facts.append("home.candidates")
            refused(report=changed)
        with self.subTest("no HOME default (chooser) and candidates unknown"):
            changed = copy.deepcopy(report)
            changed.home_candidates = [dict(c, default=False) for c in changed.home_candidates]
            changed.unresolved_facts.append("home.candidates")
            refused(report=changed)
        with self.subTest("whole package listing unresolved"):
            changed = copy.deepcopy(report)
            changed.unresolved_facts.append("packages.unparsed")
            refused(report=changed)

        # API 23 has no HOME candidate query; a resolved preferred default is still owned.
        legacy = copy.deepcopy(report)
        legacy.home_candidates = [{"component": "com.example.fakehome/.HomeActivity",
                                   "default": True}]
        legacy.unresolved_facts.append("home.candidates")
        validate_debloat_policy(legacy, profile, policy, candidates)
        self.assertEqual(fake.mutating_calls, [])


class PolicyGateTest(NoProcessTestCase):

    def test_policy_hashes_and_qualification_required(self):
        report, profile, policy, candidates, fake = qualified_case()
        validate_debloat_policy(report, profile, policy, candidates)

        def refused(report=report, profile=profile, policy=policy, candidates=candidates):
            with self.assertRaises(PolicyError):
                validate_debloat_policy(report, profile, policy, candidates)

        for status in ("unqualified", "reported", "device-tested", "QUALIFIED", None):
            with self.subTest(status=status):
                refused(policy=dict(policy, status=status))
        with self.subTest("user-imported reviewed flag is not a field"):
            refused(policy=dict(policy, status="unqualified", reviewed=True))
            refused(policy=dict(policy, reviewed=True))
        with self.subTest("no reviewer"):
            refused(policy=dict(policy, qualification={"reviewers": [],
                                                       "evidence": ["x"]}))
        with self.subTest("no evidence"):
            refused(policy=dict(policy, qualification={"reviewers": ["maintainer:fake"],
                                                       "evidence": []}))
        with self.subTest("subset of the reviewed list"):
            refused(candidates=[])
        with self.subTest("list hash differs"):
            refused(policy=dict(policy, candidateListHash="0" * 64))
        with self.subTest("profile changed after review"):
            changed = copy.deepcopy(profile)
            changed["revision"] = 2
            refused(profile=changed)
        with self.subTest("profile not structurally valid"):
            changed = copy.deepcopy(profile)
            changed["commands"] = ["reboot"]
            refused(profile=changed, policy=dict(policy, profileHash=profile_hash(changed)))
        with self.subTest("profile id differs"):
            refused(policy=dict(policy, profileId="other"))
        with self.subTest("firmware differs"):
            changed = copy.deepcopy(report)
            changed.environment["buildProperties"]["fingerprint"] += "2"
            refused(report=changed)
            refused(policy=dict(policy, firmwareHash=None))
        with self.subTest("profile does not exactly match"):
            changed = copy.deepcopy(report)
            del changed.environment["buildProperties"]["board"]
            refused(report=changed, policy=dict(
                policy, firmwareHash=environment_key(changed.environment)))
        with self.subTest("read-only target"):
            changed = copy.deepcopy(report)
            changed.read_only = True
            refused(report=changed)
        with self.subTest("no identity"):
            changed = copy.deepcopy(report)
            changed.identity = None
            refused(report=changed)
        with self.subTest("malformed policy"):
            for bad in ({}, dict(policy, policyVersion=2), dict(policy, protectedComponents="x"),
                        dict(policy, candidateListHash="ABC"), [], None):
                refused(policy=bad)
        self.assertEqual(fake.mutating_calls, [])

    def test_xyauto_reference_policy_starts_unqualified(self):
        policy = load_policy(XYAUTO_POLICY)
        self.assertEqual(policy["status"], "unqualified")
        self.assertEqual(policy["profileId"], "xyauto-yt3518c")
        self.assertIsNone(policy["firmwareHash"])
        historical = validate_candidates(
            XYAUTO_LIST.read_text(encoding="utf-8").splitlines(keepends=True))
        self.assertEqual(policy["candidateListHash"], candidate_list_hash(historical))
        profile = load_profile(XYAUTO_PROFILE, 1)
        self.assertEqual(policy["profileHash"], profile_hash(profile))

        report, fake = discovered("discovery-api23-aftermarket.json")
        with self.assertRaises(PolicyError):
            validate_debloat_policy(report, profile, policy, historical)
        for forged in (dict(policy, status="qualified"),
                       dict(policy, reviewed=True),
                       dict(policy, status="qualified",
                            firmwareHash=environment_key(report.environment))):
            changed = sorted(k for k in forged if policy.get(k, object()) != forged[k])
            with self.subTest(forged=changed), self.assertRaises(PolicyError):
                validate_debloat_policy(report, profile, forged, historical)
        self.assertEqual(fake.mutating_calls, [])


class SanitizeTest(NoProcessTestCase):

    def test_export_omits_identifiers_networks_accounts_coordinates_and_raw_logs(self):
        report, fake = discovered("discovery-api30-aftermarket.json")
        self.assertIsNotNone(report.identity)
        report.packages["com.personal.bank.account"] = {"system": False, "version_code": 9}
        report.package_flags["com.personal.bank.account"] = 0
        report.unresolved_facts.append("package_flags.com.personal.secretapp")
        report.environment["buildProperties"]["serial"] = "FAKEHWSN00000042"
        report.environment["wifi"] = "192.0.2.10"
        report.display["location"] = "10.776,106.700"
        report.raw_logs = "logcat ... user@example.com ..."
        report.accounts = ["user@example.com"]
        report.coordinates = {"lat": 10.776, "lng": 106.7}
        report.network = {"ip": "192.0.2.10", "ssid": "HomeWifi"}

        exported = sanitize(report)
        text = json.dumps(exported, sort_keys=True)
        self.assertNotIn("raw_logs", exported)
        for secret in ("FAKEHWSN00000042", "8a7b6c5d4e3f2a1b", "192.0.2.10", NET,
                       "user@example.com", "10.776", "106.7", "HomeWifi",
                       report.identity.binding_hash, "binding", "identity", "android_user",
                       "com.personal.bank.account", "com.personal.secretapp",
                       "com.example.fakehome", "serial", "wifi", "location", "logcat"):
            with self.subTest(secret=secret):
                self.assertNotIn(secret, text)
        self.assertEqual(set(exported), {"reportVersion", "environment", "display",
                                         "command_capabilities", "system_packages",
                                         "home_candidates", "unresolved_facts",
                                         "review_required"})
        self.assertIs(exported["review_required"], True)
        self.assertEqual(exported["environment"]["sdk"], 30)
        self.assertEqual(set(exported["environment"]), {"platform", "sdk", "buildProperties"})
        self.assertEqual(exported["environment"]["buildProperties"]["board"], "fakeboard_v2")
        self.assertEqual(set(exported["display"]), {"width", "height", "density"})
        self.assertEqual(sorted(exported["system_packages"]),
                         ["android", "com.android.launcher3", "com.example.fakebloat"])
        self.assertEqual([c["component"] for c in exported["home_candidates"]],
                         ["com.android.launcher3/.Launcher"])
        json.dumps(exported, allow_nan=False)
        self.assertEqual(fake.mutating_calls, [])


if __name__ == "__main__":
    unittest.main()
