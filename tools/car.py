#!/usr/bin/env python3
"""CleanYourCar device tooling. Every device command needs an explicit --serial and --user."""

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from car_support.adb import AdbClient, AdbError  # noqa: E402
from car_support.profiles import DiscoveryError, discover  # noqa: E402


def _parser():
    parser = argparse.ArgumentParser(prog="car.py", description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    disc = commands.add_parser("discover", help="read-only discovery of one selected device")
    disc.add_argument("--serial", required=True,
                      help="ADB transport serial of the one device to read (never guessed)")
    disc.add_argument("--user", required=True, type=int, help="Android user ID, e.g. 0")
    disc.add_argument("--output", required=True, type=Path,
                      help="private report path; keep it out of Git and shared profiles")
    disc.add_argument("--adb", help="adb executable (default: $ADB, then adb on PATH)")
    return parser


def _write_private(path, data):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temp = path.with_name(".%s.%d.tmp" % (path.name, os.getpid()))
    fd = os.open(str(temp), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(data, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(str(temp), str(path))
    except BaseException:
        if temp.exists():
            temp.unlink()
        raise


def _summary(report):
    env = report.environment
    lines = [
        "android user: %d" % report.android_user,
        "platform: %s" % env.get("platform", "unresolved"),
        "sdk: %d" % env["sdk"],
        "identity: %s" % (report.identity_source or "unavailable"),
        "writes: %s" % ("refused (read-only target)" if report.read_only
                        else "not authorized by discovery"),
    ]
    lines.extend("unresolved: %s" % fact for fact in report.unresolved_facts)
    if not report.unresolved_facts:
        lines.append("unresolved: none")
    return "\n".join(lines)


def _discover(args, parser, runner):
    adb_path = args.adb or os.environ.get("ADB") or shutil.which("adb")
    if runner is None and not adb_path:
        print("error: adb not found; pass --adb /path/to/adb", file=sys.stderr)
        return 2
    try:
        client = AdbClient(adb_path or "adb", args.serial, args.user, runner=runner)
    except ValueError as error:
        parser.error(str(error))
    try:
        report = discover(client)
    except (AdbError, DiscoveryError) as error:
        print("error: discovery failed: %s" % error, file=sys.stderr)
        return 1
    _write_private(args.output, report.to_dict())
    print(_summary(report))
    print("private report: %s" % args.output)
    return 0


def main(argv=None, runner=None):
    parser = _parser()
    args = parser.parse_args(argv)
    if args.command == "discover":
        return _discover(args, parser, runner)
    parser.error("unknown command")
    return 2


if __name__ == "__main__":
    sys.exit(main())
