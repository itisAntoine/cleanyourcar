"""Read-only discovery, shared profile validation, and reviewed debloat policy gates.

Profile parsing mirrors ``ProfileCodec`` in the app (same limits, field rules, and reason
codes) but is never more lenient: only strict standard JSON is accepted.
"""

import hashlib
import json
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional

from .adb import (BUILD_PROPERTIES, HOME_INTENT, TargetIdentity, environment_key,
                  optional_identity, read_environment)

REPORT_VERSION = 1
_PACKAGE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)*$")
_COMPONENT = re.compile(r"^([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)*)/"
                        r"(\.?[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*)$")
_PACKAGE_HEADER = re.compile(r"^  Package \[([^\]]+)\] \(")
_USER_STATE = re.compile(r"^\s+User (\d+):.*\binstalled=(true|false)\b.*\benabled=(\d+)\b")
_VERSION_CODE = re.compile(r"\bversionCode=(\d+)\b")
_FLAGS = re.compile(r"^\s+(?:pkgFlags|flags)=\[(.*)\]")
_PREFERRED_ITEM = re.compile(r"<item\b([^>]*)>(.*?)</item>", re.S)
_ATTR = re.compile(r'(\w+)="([^"]*)"')
_USER_INFO = re.compile(r"^\s*UserInfo\{([0-9]+):", re.M)
_SIZE = re.compile(r"^(Physical|Override) size:\s*(\d+)x(\d+)\s*$")
_DENSITY = re.compile(r"^(Physical|Override) density:\s*(\d+)\s*$")


class DiscoveryError(RuntimeError):
    """Discovery cannot produce a contract-valid environment for this target."""


@dataclass
class DiscoveryReport:
    android_user: int
    environment: Dict[str, object]
    packages: Dict[str, Dict[str, object]]
    package_flags: Dict[str, int]
    home_candidates: List[Dict[str, object]]
    command_capabilities: Dict[str, bool]
    unresolved_facts: List[str]
    display: Dict[str, int] = field(default_factory=dict)
    identity: Optional[TargetIdentity] = None
    identity_source: Optional[str] = None
    read_only: bool = True

    def to_dict(self):
        identity = None
        if self.identity is not None:
            identity = {"binding_hash": self.identity.binding_hash,
                        "firmware_hash": self.identity.firmware_hash,
                        "android_user": self.identity.android_user}
        return {
            "reportVersion": REPORT_VERSION,
            "android_user": self.android_user,
            "environment": self.environment,
            "packages": self.packages,
            "package_flags": self.package_flags,
            "home_candidates": self.home_candidates,
            "command_capabilities": self.command_capabilities,
            "display": self.display,
            "identity": identity,
            "identity_source": self.identity_source,
            "read_only": self.read_only,
            "unresolved_facts": self.unresolved_facts,
        }


def discover(client):
    """Read-only discovery of one explicitly selected device and Android user."""
    unresolved = []
    environment = read_environment(client)
    if environment["sdk"] is None:
        raise DiscoveryError("ro.build.version.sdk is not a readable positive integer")
    if "platform" not in environment:
        unresolved.append("platform")
    for key in sorted(BUILD_PROPERTIES):
        if key not in environment["buildProperties"]:
            unresolved.append("buildProperties." + key)

    identity, source = optional_identity(client, environment)
    if identity is None:
        unresolved.append("target_identity")

    user_confirmed = _user_exists(client)
    if not user_confirmed:
        unresolved.append("android_user")
    capabilities = _probe_capabilities(client, unresolved)
    packages, flags = _read_packages(client, capabilities, unresolved)
    packages_resolved = not ({"packages", "packages.unparsed"} & set(unresolved))
    home = _read_home(client, capabilities, unresolved)
    display = _read_display(client, unresolved)

    return DiscoveryReport(
        android_user=client.android_user,
        environment=environment,
        packages=packages,
        package_flags=flags,
        home_candidates=home,
        command_capabilities=dict(sorted(capabilities.items())),
        unresolved_facts=sorted(set(unresolved)),
        display=display,
        identity=identity,
        identity_source=source,
        read_only=identity is None or not user_confirmed or not packages_resolved,
    )


def _user_exists(client):
    """True only when 'pm list users' positively lists the selected Android user."""
    result = client.read(["pm", "list", "users"])
    if result.returncode != 0:
        return False
    return client.android_user in {int(m) for m in _USER_INFO.findall(result.stdout)}

def _per_user_command_supported(help_text, command):
    command_line = re.compile(
        r"^(?P<indent>[ \t]*)(?:pm[ \t]+)?" + re.escape(command)
        + r"(?=[ \t]|$)(?P<arguments>.*)$")
    user_option = re.compile(r"(?:^|[\s\[])--user(?:[\s=\]])")
    lines = help_text.splitlines()
    for index, line in enumerate(lines):
        match = command_line.match(line)
        if not match:
            continue
        if user_option.search(match.group("arguments")):
            return True
        if index + 1 < len(lines):
            continuation = lines[index + 1]
            indent = len(continuation) - len(continuation.lstrip(" \t"))
            if (indent > len(match.group("indent"))
                    and re.match(r"^\s*\[?--user(?:\s|=|\])", continuation)):
                return True
    return False


def _probe_capabilities(client, unresolved):
    listing = client.read(["cmd", "-l"])
    services = {line.strip() for line in listing.stdout.splitlines()}
    usage = client.read(["pm"])
    text = usage.stdout + "\n" + usage.stderr
    if "list packages" not in text:
        unresolved.append("command_capabilities.pm")
    commands = {
        "enable": "enable",
        "disable": "disable",
        "disable_user": "disable-user",
        "disable_until_used": "disable-until-used",
        "default_state": "default-state",
    }
    return {
        "cmd_package": listing.returncode == 0 and "package" in services,
        **{key: _per_user_command_supported(text, command)
           for key, command in commands.items()},
        "uninstall_user": _per_user_command_supported(text, "uninstall"),
        "list_packages_user": "list packages" in text and "--user" in text,
    }


def _read_packages(client, capabilities, unresolved):
    user = str(client.android_user)
    if capabilities["cmd_package"]:
        argv = ["cmd", "package", "list", "packages", "--user", user]
    else:
        argv = ["pm", "list", "packages", "--user", user]
    listing = client.read(argv)
    names = set()
    for line in listing.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        name = line[len("package:"):] if line.startswith("package:") else ""
        if _PACKAGE.fullmatch(name):
            names.add(name)
        else:
            unresolved.append("packages.unparsed")
    if listing.returncode != 0 or not names:
        unresolved.append("packages")
        return {}, {}

    dump = client.read(["dumpsys", "package", "packages"])
    details = _parse_package_dump(dump.stdout) if dump.returncode == 0 else {}
    packages, flags = {}, {}
    for name in sorted(names):
        info = details.get(name, {})
        packages[name] = {"system": info.get("system"),
                          "version_code": info.get("version_code")}
        if name not in details:
            unresolved.append("packages.%s.metadata" % name)
        state = info.get("users", {}).get(client.android_user)
        if state is None or not state[0]:
            unresolved.append("package_flags.%s" % name)
        else:
            flags[name] = state[1]
    return packages, flags


def _parse_package_dump(text):
    """Per-package metadata from the 'Packages:' section only (not hidden system copies)."""
    details, current, in_section, ambiguous = {}, None, False, set()
    for line in text.splitlines():
        if line.startswith("Packages:"):
            in_section = True
            continue
        if not in_section:
            continue
        if line and not line.startswith(" "):
            break
        header = _PACKAGE_HEADER.match(line)
        if header:
            name = header.group(1)
            if name in details:
                # A repeated header can be injected through multi-line text such as
                # versionName; neither block can be trusted.
                ambiguous.add(name)
            current = details.setdefault(name,
                                         {"system": False, "version_code": None, "users": {}})
            current["_name"] = name
            continue
        if current is None:
            continue
        version = _VERSION_CODE.search(line)
        if version and current["version_code"] is None:
            current["version_code"] = int(version.group(1))
        flags = _FLAGS.match(line)
        if flags and "SYSTEM" in flags.group(1).split():
            current["system"] = True
        user = _USER_STATE.match(line)
        if user:
            user_id = int(user.group(1))
            if user_id in current["users"]:
                ambiguous.add(current["_name"])
            current["users"][user_id] = (user.group(2) == "true", int(user.group(3)))
    for name in ambiguous:
        del details[name]
    for info in details.values():
        info.pop("_name", None)
    return details


def _component(line):
    match = _COMPONENT.fullmatch(line.strip())
    return (match.group(1), match.group(2)) if match else None


def _normalize(component):
    package, cls = component
    return package + "/" + (package + cls if cls.startswith(".") else cls)


def _read_home(client, capabilities, unresolved):
    user = str(client.android_user)
    if not capabilities["cmd_package"]:
        return _read_home_legacy(client, capabilities, unresolved)

    query = client.read(["cmd", "package", "query-activities", "--brief", "--user", user]
                        + list(HOME_INTENT))
    candidates = []
    if query.returncode == 0:
        for line in query.stdout.splitlines():
            component = _component(line)
            if component and "%s/%s" % component not in [c["component"] for c in candidates]:
                candidates.append({"component": "%s/%s" % component, "default": False})
    capabilities["query_activities"] = bool(candidates)
    if not candidates:
        unresolved.append("home.candidates")

    resolve = client.read(["cmd", "package", "resolve-activity", "--brief", "--user", user]
                          + list(HOME_INTENT))
    resolved = None
    if resolve.returncode == 0:
        components = [c for c in map(_component, resolve.stdout.splitlines()) if c]
        resolved = components[-1] if components else None
    capabilities["resolve_activity"] = resolved is not None
    if resolved is None:
        unresolved.append("home.default")
    elif not (resolved[0] == "android" and resolved[1].endswith("ResolverActivity")):
        matches = [c for c in candidates
                   if _normalize(tuple(c["component"].split("/", 1))) == _normalize(resolved)]
        if matches:
            matches[0]["default"] = True
        else:
            unresolved.append("home.default")
    return sorted(candidates, key=lambda c: c["component"])


def _read_home_legacy(client, capabilities, unresolved):
    """API 23 fallback: no candidate query; default HOME only from user 0 preferred activities."""
    capabilities["query_activities"] = False
    capabilities["resolve_activity"] = False
    unresolved.append("home.candidates")
    if client.android_user != 0:
        capabilities["preferred_xml"] = False
        unresolved.append("home.default")
        return []
    dump = client.read(["dumpsys", "package", "preferred-xml"])
    capabilities["preferred_xml"] = (dump.returncode == 0
                                     and "<preferred-activities" in dump.stdout)
    homes = []
    for attrs, body in _PREFERRED_ITEM.findall(dump.stdout):
        values = dict(_ATTR.findall(attrs))
        if (values.get("always") == "true"
                and 'name="android.intent.category.HOME"' in body
                and 'name="android.intent.action.MAIN"' in body
                and _component(values.get("name", ""))):
            homes.append(values["name"])
    if len(set(homes)) != 1:
        unresolved.append("home.default")
        return []
    return [{"component": homes[0], "default": True}]


def _read_display(client, unresolved):
    display = {}
    sizes, densities = {}, {}
    size = client.read(["wm", "size"])
    for line in size.stdout.splitlines() if size.returncode == 0 else []:
        match = _SIZE.match(line.strip())
        if match:
            sizes[match.group(1)] = (int(match.group(2)), int(match.group(3)))
    density = client.read(["wm", "density"])
    for line in density.stdout.splitlines() if density.returncode == 0 else []:
        match = _DENSITY.match(line.strip())
        if match:
            densities[match.group(1)] = int(match.group(2))
    logical_size = sizes.get("Override") or sizes.get("Physical")
    if logical_size:
        display["width"], display["height"] = logical_size
    else:
        unresolved.append("display.size")
    logical_density = densities.get("Override") or densities.get("Physical")
    if logical_density:
        display["density"] = logical_density
    else:
        unresolved.append("display.density")
    return display


# ---------------------------------------------------------------------------------------------
# Shared profile contract (schema version 1), identical to ProfileCodec/DeviceProfile.

SCHEMA_VERSION = 1
MAX_PROFILE_BYTES = 64 * 1024
MAX_PROTECTED_PACKAGES = 256
MAX_ID_BYTES = 128
MAX_PROPERTY_BYTES = 512
MAX_COMPONENT_BYTES = 512
MAX_FIRMWARE_BYTES = 512
MAX_NOTES_BYTES = 4096
MAX_REFERENCES = 16
MAX_REFERENCE_BYTES = 2048
_INT_MIN, _INT_MAX = -2 ** 31, 2 ** 31 - 1

PLATFORMS = frozenset({"aftermarket", "aaos"})
ADAPTER_IDS = frozenset({"generic", "xyauto-yt3518c"})
STRATEGY_IDS = frozenset({"launch-package", "launch-component", "google-navigation", "geo",
                          "xyauto-equalizer"})
NAVIGATION_STRATEGY_IDS = frozenset({"google-navigation", "geo"})
XYAUTO_STRATEGY_IDS = frozenset({"xyauto-equalizer"})
ROLE_KEYS = frozenset({"navigation", "bluetooth-music", "local-music", "radio", "dashcam",
                       "equalizer", "settings"})
CAPABILITY_IDS = frozenset({"app-launch", "navigation", "home", "media-transport",
                            "media-metadata", "source-selection", "reverse-detection",
                            "overlay", "brightness", "ble-receive", "lan-receive"})
CLAIM_VALUES = frozenset({"unsupported", "requires-local-check"})
BUILD_PROPERTY_KEYS = frozenset(BUILD_PROPERTIES)
EVIDENCE_CLASSIFICATIONS = frozenset({"reported", "device-tested", "maintainer-reviewed"})

_ROOT_FIELDS = frozenset({"schemaVersion", "profileId", "revision", "minAppVersionCode",
                          "platform", "adapterId", "match", "roleDefaults", "featureClaims",
                          "protectedPackages", "evidence"})
_MATCH_FIELDS = frozenset({"sdkMin", "sdkMax", "buildProperties"})
_ROLE_FIELDS = frozenset({"packageName", "componentName", "strategyId"})
_EVIDENCE_FIELDS = frozenset({"classification", "firmware", "notes", "references"})

_PROFILE_ID = re.compile(r"[a-z0-9]+([._-][a-z0-9]+)*")
# Java PACKAGE_NAME: at least two segments; ASCII only.
PACKAGE_NAME = re.compile(r"[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+")
_CLASS_NAME = re.compile(r"\.?[A-Za-z_$][A-Za-z0-9_$]*(\.[A-Za-z_$][A-Za-z0-9_$]*)*")
_HEX64 = re.compile(r"[0-9a-f]{64}")


class PolicyError(ValueError):
    """A profile, candidate list, or debloat policy fails a safety gate. Nothing may run."""


class ProfileFormatError(PolicyError):
    """Rejected profile; ``reason`` is the same stable code as ProfileFormatException."""

    def __init__(self, reason, message):
        super().__init__("%s: %s" % (reason, message))
        self.reason = reason


class _NonInteger:
    """Placeholder for JSON numbers the app's org.json would not parse as an Integer."""


_NON_INTEGER = _NonInteger()


def _strict_json(text, error):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    def reject_constant(name):
        raise ValueError("non-standard constant " + name)

    def parse_int(digits):
        return _NON_INTEGER if digits == "-0" else int(digits)

    try:
        return json.loads(text, object_pairs_hook=pairs, parse_int=parse_int,
                          parse_float=lambda _: _NON_INTEGER, parse_constant=reject_constant)
    except (ValueError, RecursionError):
        raise error


def _utf8_length(text):
    try:
        return len(text.encode("utf-8"))
    except UnicodeEncodeError:
        return None


def _is_int(value):
    return type(value) is int and _INT_MIN <= value <= _INT_MAX


def _invalid(path):
    return ProfileFormatError("invalid_value", path + " has an invalid value")


def _bad_type(path, expected):
    return ProfileFormatError("bad_type", path + " must be " + expected)


def _sorted_keys(obj, path):
    if any(not isinstance(key, str) for key in obj):
        raise _bad_type(path, "an object with string keys")
    return sorted(obj)


def _reject_unknown(obj, allowed, path):
    for key in _sorted_keys(obj, path):
        if key not in allowed:
            raise ProfileFormatError("unknown_field",
                                     "%s contains unsupported field %r" % (path, key[:64]))


def _require(obj, key):
    if key not in obj:
        raise ProfileFormatError("missing_field", key + " is required")
    return obj[key]


def _require_int(obj, key):
    value = _require(obj, key)
    if not _is_int(value):
        raise _bad_type(key, "an integer")
    return value


def _require_positive_int(obj, key):
    value = _require_int(obj, key)
    if value < 1:
        raise _invalid(key)
    return value


def _check_string(value, path, max_bytes):
    if not isinstance(value, str):
        raise _bad_type(path, "a string")
    length = _utf8_length(value)
    if not value or length is None or length > max_bytes:
        raise _invalid(path)
    return value


def _require_string(obj, key, max_bytes):
    return _check_string(_require(obj, key), key, max_bytes)


def _require_object(obj, key, parent):
    value = _require(obj, key)
    if not isinstance(value, dict):
        raise _bad_type(parent + "." + key, "an object")
    return value


def _require_array(obj, key):
    value = _require(obj, key)
    if not isinstance(value, list):
        raise _bad_type(key, "an array")
    return value


def _is_component_of(component, package):
    slash = component.find("/")
    if slash < 0 or component[:slash] != package:
        return False
    return _CLASS_NAME.fullmatch(component[slash + 1:]) is not None


def parse_profile(data, app_version_code):
    """Validate raw profile bytes exactly like ProfileCodec.parse; returns the profile dict."""
    if not isinstance(data, (bytes, bytearray)):
        raise TypeError("profile data must be bytes")
    if len(data) > MAX_PROFILE_BYTES:
        raise ProfileFormatError("too_large",
                                 "profile exceeds %d UTF-8 bytes" % MAX_PROFILE_BYTES)
    try:
        text = bytes(data).decode("utf-8")
    except UnicodeDecodeError:
        raise ProfileFormatError("malformed", "profile is not valid UTF-8")
    root = _strict_json(text, ProfileFormatError("malformed", "profile is not valid JSON"))
    return validate_profile_object(root, app_version_code)


def load_profile(path, app_version_code):
    """Read and validate a version-1 profile file with the app's contract."""
    with open(Path(path), "rb") as handle:
        data = handle.read(MAX_PROFILE_BYTES + 1)
    return parse_profile(data, app_version_code)


def validate_profile_object(root, app_version_code):
    """Validate an already-decoded profile; the check order matches ProfileCodec.parse."""
    if not _is_int(app_version_code):
        raise TypeError("app_version_code must be an integer")
    if not isinstance(root, dict):
        raise _bad_type("profile", "an object")
    schema_version = _require_int(root, "schemaVersion")
    if schema_version != SCHEMA_VERSION:
        raise ProfileFormatError("unsupported_schema",
                                 "schemaVersion %d is not supported" % schema_version)
    min_app = _require_positive_int(root, "minAppVersionCode")
    if min_app > app_version_code:
        raise ProfileFormatError("app_too_old", "profile requires app version %d" % min_app)
    _reject_unknown(root, _ROOT_FIELDS, "profile")

    if not _PROFILE_ID.fullmatch(_require_string(root, "profileId", MAX_ID_BYTES)):
        raise _invalid("profileId")
    _require_positive_int(root, "revision")
    if _require_string(root, "platform", MAX_ID_BYTES) not in PLATFORMS:
        raise _invalid("platform")
    adapter_id = _require_string(root, "adapterId", MAX_ID_BYTES)
    if adapter_id not in ADAPTER_IDS:
        raise ProfileFormatError("unknown_adapter", "adapterId is not built in")

    _check_match(_require_object(root, "match", "profile"))
    _check_roles(_require_object(root, "roleDefaults", "profile"), adapter_id)
    _check_claims(_require_object(root, "featureClaims", "profile"))
    _check_protected_packages(root)
    _check_evidence(_require_object(root, "evidence", "profile"))
    return json.loads(json.dumps(root))


def _check_match(match):
    _reject_unknown(match, _MATCH_FIELDS, "match")
    sdk_min = _require_positive_int(match, "sdkMin")
    sdk_max = _require_positive_int(match, "sdkMax")
    if sdk_max < sdk_min:
        raise _invalid("match.sdkMax")
    properties = _require_object(match, "buildProperties", "match")
    _reject_unknown(properties, BUILD_PROPERTY_KEYS, "match.buildProperties")
    for key in _sorted_keys(properties, "match.buildProperties"):
        _require_string(properties, key, MAX_PROPERTY_BYTES)


def _check_roles(roles, adapter_id):
    _reject_unknown(roles, ROLE_KEYS, "roleDefaults")
    for role in _sorted_keys(roles, "roleDefaults"):
        path = "roleDefaults." + role
        entry = _require_object(roles, role, "roleDefaults")
        _reject_unknown(entry, _ROLE_FIELDS, path)
        package = _require_string(entry, "packageName", MAX_ID_BYTES)
        if not PACKAGE_NAME.fullmatch(package):
            raise _invalid("packageName")
        component = None
        if "componentName" in entry:
            component = _require_string(entry, "componentName", MAX_COMPONENT_BYTES)
            if not _is_component_of(component, package):
                raise _invalid(path + ".componentName")
        strategy = _require_string(entry, "strategyId", MAX_ID_BYTES)
        if strategy not in STRATEGY_IDS:
            raise ProfileFormatError("unknown_strategy", path + ".strategyId is not built in")
        if strategy in NAVIGATION_STRATEGY_IDS and role != "navigation":
            raise _invalid(path + ".strategyId")
        if strategy in XYAUTO_STRATEGY_IDS and (adapter_id != "xyauto-yt3518c"
                                                or role != "equalizer"):
            raise _invalid(path + ".strategyId")
        if strategy == "launch-component" and component is None:
            raise _invalid(path + ".componentName")


def _check_claims(claims):
    _reject_unknown(claims, CAPABILITY_IDS, "featureClaims")
    for capability in _sorted_keys(claims, "featureClaims"):
        if _require_string(claims, capability, MAX_ID_BYTES) not in CLAIM_VALUES:
            raise _invalid("featureClaims." + capability)


def _check_protected_packages(root):
    packages = _require_array(root, "protectedPackages")
    if len(packages) > MAX_PROTECTED_PACKAGES:
        raise ProfileFormatError("too_many_packages", "at most %d protected packages are "
                                 "allowed" % MAX_PROTECTED_PACKAGES)
    seen = set()
    for index, value in enumerate(packages):
        path = "protectedPackages[%d]" % index
        package = _check_string(value, path, MAX_ID_BYTES)
        if not PACKAGE_NAME.fullmatch(package) or package in seen:
            raise _invalid(path)
        seen.add(package)


def _check_evidence(evidence):
    _reject_unknown(evidence, _EVIDENCE_FIELDS, "evidence")
    if _require_string(evidence, "classification", MAX_ID_BYTES) not in EVIDENCE_CLASSIFICATIONS:
        raise _invalid("evidence.classification")
    if "firmware" in evidence:
        _check_string(evidence["firmware"], "firmware", MAX_FIRMWARE_BYTES)
    if "notes" in evidence:
        _check_string(evidence["notes"], "notes", MAX_NOTES_BYTES)
    if "references" in evidence:
        references = _require_array(evidence, "references")
        if len(references) > MAX_REFERENCES:
            raise _invalid("evidence.references")
        for index, value in enumerate(references):
            _check_string(value, "evidence.references[%d]" % index, MAX_REFERENCE_BYTES)


def profile_hash(profile):
    """SHA-256 over the canonical JSON form of a validated profile."""
    canonical = json.dumps(profile, sort_keys=True, separators=(",", ":"), ensure_ascii=False,
                           allow_nan=False)
    return hashlib.sha256(("cleanyourcar-profile-v1\n" + canonical).encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------------------------
# Unconditional protections. A candidate is never safe merely because it is absent from these
# sets; it must also be in the exact reviewed candidate list of a qualified firmware policy.

CORE_PROTECTED_PACKAGES = frozenset({
    # Framework, UI shell, telephony, input, storage, location.
    "android", "com.android.systemui", "com.android.settings", "com.android.phone",
    "com.android.server.telecom", "com.android.defcontainer", "com.android.inputmethod.latin",
    "com.android.shell", "com.android.externalstorage", "com.android.location.fused",
    "com.android.bluetooth",
    # Permission controllers and package installers.
    "com.android.permissioncontroller", "com.google.android.permissioncontroller",
    "com.android.packageinstaller", "com.google.android.packageinstaller",
    # Ext services/shared libraries and mainline module metadata.
    "com.android.ext.services", "com.google.android.ext.services",
    "com.android.ext.shared", "com.google.android.ext.shared",
    "com.android.modulemetadata", "com.google.android.modulemetadata",
    # Core content providers.
    "com.android.providers.settings", "com.android.providers.media",
    "com.android.providers.media.module", "com.google.android.providers.media.module",
    "com.android.providers.downloads", "com.android.providers.downloads.ui",
    "com.android.providers.telephony", "com.android.providers.contacts",
    "com.android.providers.userdictionary", "com.android.providers.blockednumber",
    # Stock HOME fallback and this project's own packages.
    "com.android.launcher3", "com.cleanyourcar.launcher", "com.cleanyourcar.navbridge",
})

# Firmware-specific vehicle packages (reverse camera, Bluetooth, CAN/MCU, brake check, audio
# DSP, dashcam, USB music, radio) and the reference profile's navigation/GMS dependencies.
VEHICLE_PROTECTED_PACKAGES = {
    "xyauto-yt3518c": frozenset({
        "com.autochips.android.backcar", "com.autochips.bluetooth", "com.xygala.canbus",
        "com.xygala.pvcanset", "com.xy.spl", "com.xy.brakecheck", "com.xyauto.Settings",
        "com.fvsm.camera", "com.acloud.stub.localmusic", "com.acloud.stub.localradio",
        "com.mediatek.usbchecker", "com.google.android.apps.maps", "com.google.android.gms",
    }),
}

PROTECTED_PACKAGES = frozenset(CORE_PROTECTED_PACKAGES.union(*VEHICLE_PROTECTED_PACKAGES.values()))
MAX_CANDIDATES = 256


def validate_candidates(lines):
    """Normalize (BOM, CR/LF, comments, blanks) then strictly validate a debloat list.

    Returns the package names in order. Refuses non-package syntax, empty lists,
    duplicates, oversize lists, and every unconditionally protected package.
    """
    if lines is None or isinstance(lines, (str, bytes, bytearray)):
        raise PolicyError("candidate list must be a sequence of lines")
    packages, seen = [], set()
    for number, raw in enumerate(lines, 1):
        if not isinstance(raw, str):
            raise PolicyError("line %d is not text" % number)
        line = raw[1:] if number == 1 and raw.startswith("\ufeff") else raw
        line = line.rstrip("\r\n").split("#", 1)[0].strip(" \t")
        if not line:
            continue
        length = _utf8_length(line)
        if length is None or length > MAX_ID_BYTES or not PACKAGE_NAME.fullmatch(line):
            raise PolicyError("line %d is not a package name: %r" % (number, line[:64]))
        if line in PROTECTED_PACKAGES:
            raise PolicyError("line %d names protected package %s" % (number, line))
        if line in seen:
            raise PolicyError("line %d duplicates %s" % (number, line))
        seen.add(line)
        packages.append(line)
        if len(packages) > MAX_CANDIDATES:
            raise PolicyError("more than %d candidates" % MAX_CANDIDATES)
    if not packages:
        raise PolicyError("candidate list contains no packages")
    return packages


def candidate_list_hash(candidates):
    """SHA-256 binding the exact normalized candidate list, in order."""
    if isinstance(candidates, (str, bytes)) or not all(isinstance(c, str) for c in candidates):
        raise PolicyError("candidates must be a list of package names")
    body = "".join(name + "\n" for name in candidates)
    return hashlib.sha256(("cleanyourcar-candidates-v1\n" + body).encode("utf-8")).hexdigest()


# ---------------------------------------------------------------------------------------------
# Debloat qualification policy (one per firmware candidate list).

POLICY_VERSION = 1
POLICY_STATUSES = frozenset({"unqualified", "qualified"})
# Release-maintainer pins over complete policy content, never imported authorization.
# Empty until a bundled policy has actual firmware/dependency qualification evidence.
_TRUSTED_QUALIFIED_POLICY_HASHES = frozenset()
_POLICY_FIELDS = frozenset({"policyVersion", "profileId", "profileHash", "firmwareHash",
                            "candidateList", "candidateListHash", "status",
                            "protectedComponents", "unresolvedDependencies", "qualification",
                            "notes"})
_POLICY_REQUIRED = _POLICY_FIELDS - {"notes"}
_QUALIFICATION_FIELDS = frozenset({"reviewers", "evidence"})
_DEPENDENCY_FIELDS = frozenset({"package", "reason"})


def load_policy(path):
    """Read and structurally validate a debloat policy file (not a write authorization)."""
    with open(Path(path), "rb") as handle:
        data = handle.read(MAX_PROFILE_BYTES + 1)
    if len(data) > MAX_PROFILE_BYTES:
        raise PolicyError("policy exceeds %d bytes" % MAX_PROFILE_BYTES)
    try:
        text = data.decode("utf-8")
    except UnicodeDecodeError:
        raise PolicyError("policy is not valid UTF-8")
    return _check_policy(_strict_json(text, PolicyError("policy is not valid JSON")))


def _policy_strings(value, path, max_items, max_bytes):
    if not isinstance(value, list) or len(value) > max_items:
        raise PolicyError(path + " must be a list of at most %d strings" % max_items)
    for index, item in enumerate(value):
        length = _utf8_length(item) if isinstance(item, str) else None
        if not item or length is None or length > max_bytes:
            raise PolicyError("%s[%d] is invalid" % (path, index))
    return value


def _check_policy(policy):
    if not isinstance(policy, dict) or any(not isinstance(k, str) for k in policy):
        raise PolicyError("policy must be an object")
    unknown = sorted(set(policy) - _POLICY_FIELDS)
    if unknown:
        raise PolicyError("policy contains unsupported field %r" % unknown[0][:64])
    missing = sorted(_POLICY_REQUIRED - set(policy))
    if missing:
        raise PolicyError("policy field %s is required" % missing[0])
    if not _is_int(policy["policyVersion"]) or policy["policyVersion"] != POLICY_VERSION:
        raise PolicyError("policyVersion is not supported")
    if not isinstance(policy["profileId"], str) or not _PROFILE_ID.fullmatch(
            policy["profileId"]) or len(policy["profileId"]) > MAX_ID_BYTES:
        raise PolicyError("profileId is invalid")
    for key in ("profileHash", "firmwareHash"):
        value = policy[key]
        if value is not None and not (isinstance(value, str) and _HEX64.fullmatch(value)):
            raise PolicyError(key + " must be null or a SHA-256 hex digest")
    if not (isinstance(policy["candidateListHash"], str)
            and _HEX64.fullmatch(policy["candidateListHash"])):
        raise PolicyError("candidateListHash must be a SHA-256 hex digest")
    _policy_strings([policy["candidateList"]], "candidateList", 1, MAX_PROPERTY_BYTES)
    if "notes" in policy:
        _policy_strings([policy["notes"]], "notes", 1, MAX_NOTES_BYTES)
    if not isinstance(policy["status"], str) or policy["status"] not in POLICY_STATUSES:
        raise PolicyError("status must be one of %s" % ", ".join(sorted(POLICY_STATUSES)))

    components = _policy_strings(policy["protectedComponents"], "protectedComponents",
                                 MAX_PROTECTED_PACKAGES, MAX_COMPONENT_BYTES)
    for component in components:
        package = component.split("/", 1)[0]
        if not PACKAGE_NAME.fullmatch(package) or (
                "/" in component and not _is_component_of(component, package)):
            raise PolicyError("protectedComponents entry %r is invalid" % component[:64])
    if len(set(components)) != len(components):
        raise PolicyError("protectedComponents contains duplicates")

    dependencies = policy["unresolvedDependencies"]
    if not isinstance(dependencies, list) or len(dependencies) > MAX_PROTECTED_PACKAGES:
        raise PolicyError("unresolvedDependencies must be a list")
    for index, entry in enumerate(dependencies):
        if not isinstance(entry, dict) or set(entry) != _DEPENDENCY_FIELDS:
            raise PolicyError("unresolvedDependencies[%d] must have package and reason" % index)
        if not isinstance(entry["package"], str) or not PACKAGE_NAME.fullmatch(entry["package"]):
            raise PolicyError("unresolvedDependencies[%d].package is invalid" % index)
        _policy_strings([entry["reason"]], "unresolvedDependencies[%d].reason" % index, 1,
                        MAX_NOTES_BYTES)

    qualification = policy["qualification"]
    if not isinstance(qualification, dict) or set(qualification) != _QUALIFICATION_FIELDS:
        raise PolicyError("qualification must have exactly reviewers and evidence")
    reviewers = _policy_strings(qualification["reviewers"], "qualification.reviewers",
                                MAX_REFERENCES, MAX_ID_BYTES)
    evidence = _policy_strings(qualification["evidence"], "qualification.evidence",
                               MAX_REFERENCES, MAX_REFERENCE_BYTES)

    if policy["status"] == "qualified":
        if policy["profileHash"] is None or policy["firmwareHash"] is None:
            raise PolicyError("a qualified policy must bind exact profile and firmware hashes")
        if not reviewers or not evidence:
            raise PolicyError("a qualified policy needs reviewer and evidence references")
        if dependencies:
            raise PolicyError("a qualified policy cannot have unresolved dependencies")
    return policy


def validate_debloat_policy(report, profile, policy, candidates):
    """Raise PolicyError unless every debloat write gate passes. Reads nothing from a device."""
    policy = _check_policy(policy)
    if policy["status"] != "qualified":
        raise PolicyError("debloat policy for %s is %s; writes are refused"
                          % (policy["profileId"], policy["status"]))
    content = json.dumps(policy, sort_keys=True, separators=(",", ":"), ensure_ascii=True)
    digest = hashlib.sha256(content.encode("utf-8")).hexdigest()
    if digest not in _TRUSTED_QUALIFIED_POLICY_HASHES:
        raise PolicyError("policy has no trusted bundled qualification; writes are refused")
    try:
        profile = validate_profile_object(profile, _INT_MAX)
    except ProfileFormatError as error:
        raise PolicyError("profile is invalid: %s" % error)
    if profile["profileId"] != policy["profileId"]:
        raise PolicyError("policy is for another profile")
    if profile_hash(profile) != policy["profileHash"]:
        raise PolicyError("profile changed since the policy was reviewed")

    _check_target(report, policy)
    _check_exact_match(report.environment, profile)

    names = validate_candidates(candidates)
    if candidate_list_hash(names) != policy["candidateListHash"]:
        raise PolicyError("candidate list differs from the reviewed list")

    protected = set(profile["protectedPackages"])
    protected.update(VEHICLE_PROTECTED_PACKAGES.get(profile["adapterId"], ()))
    for role in profile["roleDefaults"].values():
        protected.add(role["packageName"])
    protected.update(c.split("/", 1)[0] for c in policy["protectedComponents"])
    unresolved_dependencies = {d["package"] for d in policy["unresolvedDependencies"]}
    home_packages = _home_packages(report)
    for name in names:
        if name in protected:
            raise PolicyError("%s is protected by the profile or policy" % name)
        if name in unresolved_dependencies:
            raise PolicyError("%s has unresolved dependencies" % name)
        if name in home_packages:
            raise PolicyError("%s provides HOME; switching HOME is not part of debloat" % name)
        _check_package_facts(report, name)


def _check_target(report, policy):
    unresolved = set(report.unresolved_facts)
    if report.read_only or report.identity is None:
        raise PolicyError("target is read-only (identity or package facts unresolved)")
    blocking = unresolved & {"packages", "packages.unparsed", "android_user",
                             "target_identity", "platform"}
    if blocking:
        raise PolicyError("required facts unresolved: %s" % ", ".join(sorted(blocking)))
    if report.identity.android_user != report.android_user:
        raise PolicyError("identity is bound to another Android user")
    firmware = environment_key(report.environment)
    if firmware != report.identity.firmware_hash or firmware != policy["firmwareHash"]:
        raise PolicyError("firmware does not match the qualified firmware")


def _check_exact_match(environment, profile):
    match = profile["match"]
    declared = match["buildProperties"]
    if (environment.get("platform") != profile["platform"]
            or not match["sdkMin"] <= environment["sdk"] <= match["sdkMax"]):
        raise PolicyError("profile platform or SDK range does not match")
    if not declared:
        raise PolicyError("a generic fallback profile is never an exact firmware match")
    actual = environment["buildProperties"]
    for key, value in sorted(declared.items()):
        if actual.get(key) != value:
            raise PolicyError("build property %s does not exactly match" % key)


def _home_packages(report):
    unresolved = set(report.unresolved_facts)
    if "home.default" in unresolved:
        raise PolicyError("default HOME is unresolved")
    if "home.candidates" in unresolved:
        raise PolicyError("HOME ownership is unknown")
    return {c["component"].split("/", 1)[0] for c in report.home_candidates}


def _check_package_facts(report, name):
    unresolved = set(report.unresolved_facts)
    info = report.packages.get(name)
    if info is None:
        raise PolicyError("%s is not installed for this user" % name)
    if not isinstance(info.get("system"), bool) or ("packages.%s.metadata" % name) in unresolved:
        raise PolicyError("%s metadata is unresolved" % name)
    if (("package_flags.%s" % name) in unresolved
            or type(report.package_flags.get(name)) is not int):
        raise PolicyError("%s has no exact original enabled flag" % name)


# ---------------------------------------------------------------------------------------------
# Sanitized export: an allowlist of public firmware facts. Anything not listed is dropped.

_GENERAL_FACTS = frozenset({"platform", "command_capabilities.pm", "packages",
                            "packages.unparsed", "home.candidates", "home.default",
                            "display.size", "display.density"}
                           | {"buildProperties." + key for key in BUILD_PROPERTY_KEYS})
_CAPABILITY_KEY = re.compile(r"[a-z][a-z0-9_]{0,63}")


def sanitize(report):
    """Public profile/report fields only. Requires user review before sharing."""
    source = report.environment
    environment = {}
    if source.get("platform") in PLATFORMS:
        environment["platform"] = source["platform"]
    if _is_int(source.get("sdk")):
        environment["sdk"] = source["sdk"]
    environment["buildProperties"] = {
        key: value for key, value in sorted(source.get("buildProperties", {}).items())
        if key in BUILD_PROPERTY_KEYS and isinstance(value, str)}

    display = {key: report.display[key] for key in ("width", "height", "density")
               if _is_int(report.display.get(key))}
    capabilities = {key: value for key, value in sorted(report.command_capabilities.items())
                    if isinstance(key, str) and _CAPABILITY_KEY.fullmatch(key)
                    and isinstance(value, bool)}
    system = {}
    for name, info in sorted(report.packages.items()):
        if PACKAGE_NAME.fullmatch(name) or name == "android":
            if isinstance(info, dict) and info.get("system") is True:
                version = info.get("version_code")
                system[name] = {"version_code": version if _is_int(version) else None}
    homes = [{"component": c["component"], "default": c.get("default") is True}
             for c in report.home_candidates
             if isinstance(c.get("component"), str) and c["component"].split("/", 1)[0] in system]
    facts = []
    for fact in report.unresolved_facts:
        if fact in _GENERAL_FACTS:
            facts.append(fact)
        elif fact.startswith("package_flags.") and fact[len("package_flags."):] in system:
            facts.append(fact)
        elif (fact.startswith("packages.") and fact.endswith(".metadata")
              and fact[len("packages."):-len(".metadata")] in system):
            facts.append(fact)
    return {
        "reportVersion": REPORT_VERSION,
        "environment": environment,
        "display": display,
        "command_capabilities": capabilities,
        "system_packages": system,
        "home_candidates": homes,
        "unresolved_facts": sorted(set(facts)),
        "review_required": True,
    }
