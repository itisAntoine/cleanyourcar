"""Read-only device discovery producing facts in the shared profile contract."""

import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from .adb import (BUILD_PROPERTIES, HOME_INTENT, TargetIdentity, optional_identity,
                  read_environment)

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
    if environment["platform"] is None:
        unresolved.append("platform")
    for key in sorted(BUILD_PROPERTIES):
        if key not in environment["buildProperties"]:
            unresolved.append("buildProperties." + key)

    identity, source = optional_identity(client, environment)
    if identity is None:
        unresolved.append("target_identity")

    capabilities = _probe_capabilities(client, unresolved)
    packages, flags = _read_packages(client, capabilities, unresolved)
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
        read_only=identity is None,
    )


def _probe_capabilities(client, unresolved):
    listing = client.read(["cmd", "-l"])
    services = {line.strip() for line in listing.stdout.splitlines()}
    usage = client.read(["pm"])
    text = usage.stdout + "\n" + usage.stderr
    if "list packages" not in text:
        unresolved.append("command_capabilities.pm")
    return {
        "cmd_package": listing.returncode == 0 and "package" in services,
        "disable_user": "disable-user" in text,
        "default_state": "default-state" in text,
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
        packages[name] = {"system": info.get("system", False),
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
    details, current, in_section = {}, None, False
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
            current = details.setdefault(header.group(1),
                                         {"system": False, "version_code": None, "users": {}})
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
            current["users"][int(user.group(1))] = (user.group(2) == "true",
                                                    int(user.group(3)))
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
