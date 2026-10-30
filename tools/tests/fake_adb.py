"""Fake ADB transport for tool tests. It never launches a process."""

import json
import shlex
from pathlib import Path

from car_support.adb import CommandResult

FIXTURES = Path(__file__).resolve().parent / "fixtures"

_PACKAGE_MUTATIONS = {
    "install", "install-create", "install-write", "install-commit", "install-existing",
    "uninstall", "enable", "disable", "disable-user", "disable-until-used", "default-state",
    "hide", "unhide", "grant", "revoke", "set-home-activity", "clear", "suspend", "unsuspend",
    "set-install-location", "reset-permissions", "set-permission-enforced",
}
_PACKAGE_READS = {"list", "query-activities", "resolve-activity"}
_HOST_MUTATIONS = {
    "install", "install-multiple", "uninstall", "push", "root", "unroot", "remount", "reboot",
    "connect", "disconnect", "pair", "tcpip", "usb", "sideload", "disable-verity",
}
_REMOTE_MUTATORS = {
    "am", "input", "monkey", "reboot", "svc", "setprop", "rm", "install", "uninstall", "su",
    "sh", "start", "stop", "content", "appops", "dpm", "ime",
}


def load_fixture(name):
    with open(FIXTURES / name, encoding="utf-8") as handle:
        return json.load(handle)["responses"]


def is_mutating_remote(tokens):
    if not tokens:
        return False
    head = tokens[0]
    if head in _REMOTE_MUTATORS:
        return True
    if head == "pm":
        return len(tokens) > 1 and tokens[1] not in _PACKAGE_READS
    if head == "cmd":
        if tokens[1:] == ["-l"]:
            return False
        return not (len(tokens) > 2 and tokens[1] == "package" and tokens[2] in _PACKAGE_READS)
    if head == "settings":
        return len(tokens) > 1 and tokens[1] != "get"
    if head == "wm":
        return len(tokens) != 2
    return False


class FakeAdb:
    """Callable runner: (host_argv, timeout) -> CommandResult, scripted from a fixture."""

    def __init__(self, responses, connected=("FAKE-USB-0001", "192.0.2.10:5555")):
        self.responses = dict(responses)
        self.connected = list(connected)
        self.calls = []
        self.remote_commands = []
        self.mutating_calls = []
        self.device_listing_calls = []
        self.unscripted = []
        self.timeouts = []

    def __call__(self, host_argv, timeout):
        host_argv = list(host_argv)
        self.calls.append(host_argv)
        self.timeouts.append(timeout)
        verb_index = 3 if len(host_argv) > 3 and host_argv[1] == "-s" else 1
        verb = host_argv[verb_index] if len(host_argv) > verb_index else ""
        if verb == "devices":
            self.device_listing_calls.append(host_argv)
            lines = ["List of devices attached"] + ["%s\tdevice" % s for s in self.connected]
            return CommandResult(0, "\n".join(lines) + "\n", "")
        if verb in _HOST_MUTATIONS:
            self.mutating_calls.append(host_argv)
            return CommandResult(1, "", "fake: host mutation refused")
        if verb != "shell":
            self.unscripted.append(host_argv)
            return CommandResult(1, "", "fake: unsupported host verb")
        remote = " ".join(host_argv[verb_index + 1:])
        self.remote_commands.append(remote)
        if is_mutating_remote(shlex.split(remote)):
            self.mutating_calls.append(host_argv)
            return CommandResult(1, "", "fake: mutation refused")
        response = self.responses.get(remote)
        if response is None:
            self.unscripted.append(host_argv)
            return CommandResult(127, "", "fake: unscripted command")
        return CommandResult(response.get("returncode", 0), response.get("stdout", ""),
                             response.get("stderr", ""))
