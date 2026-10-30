"""Explicitly targeted ADB transport and private target identity.

Every call names one device with ``adb -s <serial>`` and sends exactly one validated remote
command. No host shell is used and no device is ever selected implicitly.
"""

import hashlib
import re
import shlex
import subprocess
from dataclasses import dataclass
from typing import Callable, Optional, Sequence

DEFAULT_TIMEOUT_SECONDS = 30.0
MAX_TIMEOUT_SECONDS = 120.0

_SERIAL = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:@\-]{0,254}$")
_PROGRAM = re.compile(r"^[a-z][a-z0-9_-]*$")

PLATFORM_AFTERMARKET = "aftermarket"
PLATFORM_AAOS = "aaos"
AUTOMOTIVE_FEATURE = "feature:android.hardware.type.automotive"

# Profile contract key -> Android system property backing android.os.Build.
BUILD_PROPERTIES = {
    "board": "ro.product.board",
    "product": "ro.product.name",
    "manufacturer": "ro.product.manufacturer",
    "display": "ro.build.display.id",
    "fingerprint": "ro.build.fingerprint",
}
SDK_PROPERTY = "ro.build.version.sdk"
HARDWARE_SERIAL_PROPERTIES = ("ro.serialno", "ro.boot.serialno")
READABLE_PROPERTIES = frozenset(
    list(BUILD_PROPERTIES.values()) + [SDK_PROPERTY] + list(HARDWARE_SERIAL_PROPERTIES))

HOME_INTENT = ("-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME")

_PLACEHOLDER_SERIALS = frozenset({
    "unknown", "null", "none", "0", "0123456789abcdef", "0000000000000000", "123456789",
    "1234567890", "1234567890abcdef",
})
# Known-duplicated legacy Android ID value shipped on many devices.
_PLACEHOLDER_ANDROID_IDS = frozenset({"9774d56d682e549c"})


class AdbError(RuntimeError):
    """The transport could not run a command (missing adb, timeout, OS error)."""


class IdentityUnavailable(RuntimeError):
    """No reliable target identity; the target must be treated as read-only."""


@dataclass(frozen=True)
class CommandResult:
    returncode: int
    stdout: str
    stderr: str


@dataclass(frozen=True)
class TargetIdentity:
    binding_hash: str
    firmware_hash: str
    android_user: int


Runner = Callable[[Sequence[str], float], CommandResult]


def _subprocess_runner(host_argv, timeout):
    try:
        completed = subprocess.run(
            list(host_argv), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, timeout=timeout, check=False, shell=False,
            universal_newlines=True, encoding="utf-8", errors="replace")
    except subprocess.TimeoutExpired as error:
        raise AdbError("adb command timed out after %.0fs" % timeout) from error
    except OSError as error:
        raise AdbError("cannot run adb: %s" % error.__class__.__name__) from error
    return CommandResult(completed.returncode, completed.stdout or "", completed.stderr or "")


def validate_serial(serial):
    if not isinstance(serial, str) or not _SERIAL.fullmatch(serial):
        raise ValueError("an explicit ADB serial is required (letters, digits, . _ : @ -)")
    return serial


def validate_user(android_user):
    if type(android_user) is not int or android_user < 0:
        raise ValueError("an explicit non-negative integer Android user is required")
    return android_user


def validate_remote_argv(argv):
    if isinstance(argv, (str, bytes)) or not isinstance(argv, (list, tuple)) or not argv:
        raise ValueError("remote command must be a non-empty argument list")
    for arg in argv:
        if not isinstance(arg, str) or not arg or any(c in arg for c in "\x00\r\n"):
            raise ValueError("remote arguments must be non-empty single-line strings")
    if not _PROGRAM.fullmatch(argv[0]):
        raise ValueError("remote program must be a bare command name")
    return list(argv)


def is_read_only(argv):
    """True only for the exact read command shapes used by discovery and identity."""
    argv = list(argv)
    if len(argv) == 2 and argv[0] == "getprop":
        return argv[1] in READABLE_PROPERTIES
    if argv in (["cmd", "-l"], ["pm"], ["pm", "list", "features"], ["pm", "list", "users"]):
        return True
    if argv in (["dumpsys", "package", "packages"], ["dumpsys", "package", "preferred-xml"]):
        return True
    if argv in (["wm", "size"], ["wm", "density"]):
        return True
    if len(argv) == 6 and argv[:2] == ["settings", "get"]:
        return argv[2] == "--user" and _is_user(argv[3]) and argv[4:] == ["secure",
                                                                          "android_id"]
    if argv[:3] == ["pm", "list", "packages"] or argv[:4] == ["cmd", "package", "list",
                                                               "packages"]:
        tail = argv[3:] if argv[0] == "pm" else argv[4:]
        return len(tail) == 2 and tail[0] == "--user" and _is_user(tail[1])
    if argv[:2] == ["cmd", "package"] and len(argv) == 10 and argv[2] in (
            "query-activities", "resolve-activity"):
        return (argv[3] == "--brief" and argv[4] == "--user" and _is_user(argv[5])
                and tuple(argv[6:]) == HOME_INTENT)
    return False


def _is_user(text):
    return _decimal(text) and str(int(text)) == text


def _decimal(text):
    return re.fullmatch(r"[0-9]+", text) is not None


class AdbClient:
    """One explicitly selected device and Android user."""

    def __init__(self, adb_path, serial, android_user, runner=None,
                 timeout=DEFAULT_TIMEOUT_SECONDS):
        if not isinstance(adb_path, str) or not adb_path:
            raise ValueError("adb_path is required")
        self.adb_path = adb_path
        self.serial = validate_serial(serial)
        self.android_user = validate_user(android_user)
        if not (0 < float(timeout) <= MAX_TIMEOUT_SECONDS):
            raise ValueError("timeout must be within (0, %d] seconds" % MAX_TIMEOUT_SECONDS)
        self.timeout = float(timeout)
        self._runner = runner or _subprocess_runner

    def __repr__(self):
        return "AdbClient(user=%d)" % self.android_user

    def shell(self, argv):
        remote = shlex.join(validate_remote_argv(argv))
        host_argv = [self.adb_path, "-s", self.serial, "shell", remote]
        return self._runner(host_argv, self.timeout)

    def read(self, argv):
        """Run a command only if it is on the read-only allowlist."""
        argv = validate_remote_argv(argv)
        if not is_read_only(argv):
            raise ValueError("command is not an allowlisted read: %s" % argv[0])
        return self.shell(argv)

    def identity(self):
        return identity_from_facts(read_environment(self), read_binding_source(self),
                                   self.android_user)


def read_property(client, name):
    """Raw property value as android.os.Build stores it (only getprop's line ending removed),
    or None when unreadable, blank, or 'unknown'. Trimming is used only for that check,
    matching EnvironmentFacts."""
    result = client.read(["getprop", name])
    if result.returncode != 0:
        return None
    value = re.sub(r"\r?\n\Z", "", result.stdout)
    trimmed = value.strip()
    if not trimmed or trimmed.lower() == "unknown":
        return None
    return value


def read_sdk(client):
    """Integer API level from ro.build.version.sdk; release text is never consulted."""
    value = read_property(client, SDK_PROPERTY)
    if value is None or not _decimal(value.strip()) or int(value.strip()) < 1:
        return None
    return int(value.strip())


def read_platform(client):
    result = client.read(["pm", "list", "features"])
    lines = [line.strip() for line in result.stdout.splitlines()]
    if result.returncode != 0 or not any(line.startswith("feature:") for line in lines):
        return None
    return PLATFORM_AAOS if AUTOMOTIVE_FEATURE in lines else PLATFORM_AFTERMARKET


def read_environment(client):
    """Facts in the application profile contract: platform, sdk, buildProperties."""
    props = {}
    for key, prop in BUILD_PROPERTIES.items():
        value = read_property(client, prop)
        if value is not None:
            props[key] = value
    environment = {}
    platform = read_platform(client)
    if platform is not None:
        environment["platform"] = platform
    environment["sdk"] = read_sdk(client)
    environment["buildProperties"] = dict(sorted(props.items()))
    return environment


def environment_key(environment):
    """Same canonical form and SHA-256 as EnvironmentFacts.environmentKey() in the app."""
    lines = ["platform=%s" % environment["platform"], "sdk=%d" % environment["sdk"]]
    for key, value in sorted(environment["buildProperties"].items()):
        utf16_length = len(value.encode("utf-16-le")) // 2
        lines.append("%s=%d:%s" % (key, utf16_length, value))
    return hashlib.sha256(("\n".join(lines) + "\n").encode("utf-8")).hexdigest()


def read_binding_source(client):
    """Ordered (kind, raw value) pairs for every reliable identifier, or None.

    A hardware serial may be cloned across a production batch, so a reliable user-scoped
    Android ID is combined with it rather than ignored. Raw values are returned only to be
    hashed; callers must not log or store them.
    """
    sources = []
    for prop in HARDWARE_SERIAL_PROPERTIES:
        value = read_property(client, prop)
        if value is not None and _reliable_serial(value):
            sources.append(("hardware-serial", value))
            break
    result = client.read(["settings", "get", "--user", str(client.android_user), "secure",
                          "android_id"])
    value = result.stdout.strip().lower() if result.returncode == 0 else ""
    if _reliable_android_id(value):
        sources.append(("android-id", value))
    return tuple(sources) or None


def source_name(sources):
    return "+".join(kind for kind, _ in sources)


def identity_from_facts(environment, source, android_user):
    if environment.get("platform") is None or environment.get("sdk") is None:
        raise IdentityUnavailable("platform or SDK facts are unavailable")
    if source is None:
        raise IdentityUnavailable("no reliable hardware serial or Android ID is readable")
    kinds = [kind for kind, _ in source]
    props = environment["buildProperties"]
    stable = {key: props[key] for key in ("manufacturer", "board", "product") if key in props}
    if kinds == ["android-id"] and not stable:
        raise IdentityUnavailable("Android ID needs manufacturer/board/product facts")
    parts = ["cleanyourcar-target-binding-v2", "source=" + source_name(source)]
    parts.extend("%s=%d:%s" % (kind, len(raw), raw) for kind, raw in source)
    parts.append("platform=" + environment["platform"])
    if "android-id" in kinds:
        parts.append("user=%d" % android_user)
    parts.extend("%s=%d:%s" % (key, len(value), value) for key, value in sorted(stable.items()))
    binding = hashlib.sha256(("\n".join(parts) + "\n").encode("utf-8")).hexdigest()
    return TargetIdentity(binding, environment_key(environment), android_user)


def _reliable_serial(value):
    if not re.fullmatch(r"[A-Za-z0-9._-]{6,64}", value):
        return False
    lowered = value.lower()
    return lowered not in _PLACEHOLDER_SERIALS and len(set(lowered)) > 1


def _reliable_android_id(value):
    if not re.fullmatch(r"[0-9a-f]{16}", value):
        return False
    return value not in _PLACEHOLDER_ANDROID_IDS and len(set(value)) > 1


def optional_identity(client, environment):
    """(TargetIdentity, source name) or (None, None) when the target must stay read-only."""
    source = read_binding_source(client)
    try:
        return identity_from_facts(environment, source, client.android_user), source_name(source)
    except IdentityUnavailable:
        return None, None
