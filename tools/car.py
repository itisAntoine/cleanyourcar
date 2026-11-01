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
from car_support.profiles import load_policy, load_profile, PolicyError  # noqa: E402
from car_support.operations import (apply, ApplyResult, plan_debloat, plan_install,  # noqa: E402
                                    plan_restore, PlanError)
from car_support.records import RecordStore, RecordError, write_private_json  # noqa: E402


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
    for name in ("plan-debloat", "plan-install", "plan-restore"):
        preview = commands.add_parser(name, help="read-only, target-bound private preview")
        preview.add_argument("--serial", required=True, help="explicit selected ADB transport")
        preview.add_argument("--user", required=True, type=int, help="explicit Android user")
        preview.add_argument("--adb", help="adb executable (default: $ADB, then adb on PATH)")
        preview.add_argument("--output", required=True, type=Path,
                             help="private preview path in a directory with mode 0700")
        preview.add_argument("--records", type=Path,
                             help="private record root (default: ~/.cleanyourcar/records)")
        if name == "plan-restore":
            preview.add_argument("--operation-id", required=True, help="source recovery operation ID")
        else:
            preview.add_argument("--profile", required=True, type=Path)
            preview.add_argument("--app-version-code", type=int, default=1)
            if name == "plan-debloat":
                preview.add_argument("--policy", required=True, type=Path)
                preview.add_argument("--candidates", required=True, type=Path)
            else:
                preview.add_argument("--apk-metadata", required=True, type=Path,
                                     help="local JSON package/version_code/min_sdk/sha256/path; "
                                          "verified against actual APK bytes with existing SDK aapt2")
    execution = commands.add_parser("apply", help="apply one explicitly confirmed saved private plan")
    execution.add_argument("--serial", required=True)
    execution.add_argument("--user", required=True, type=int)
    execution.add_argument("--adb", help="adb executable (default: $ADB, then adb on PATH)")
    execution.add_argument("--records", type=Path, help="same private root used for the preview")
    execution.add_argument("--plan", required=True, type=Path, help="saved private preview")
    execution.add_argument("--confirm", required=True, help="exact preview confirmation hash")
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
    if args.command.startswith("plan-"):
        return _preview(args, parser, runner)
    if args.command == "apply":
        return _apply(args, parser, runner)
    parser.error("unknown command")
    return 2


def _preview(args, parser, runner):
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
        store = RecordStore(args.records)
        if args.command == "plan-restore":
            plan = plan_restore(store.load(args.operation_id), report)
        else:
            profile = load_profile(args.profile, args.app_version_code)
            if args.command == "plan-debloat":
                policy = load_policy(args.policy)
                candidates = args.candidates.read_text(encoding="utf-8").splitlines()
                plan = plan_debloat(report, profile, policy, candidates)
            else:
                metadata = json.loads(args.apk_metadata.read_text(encoding="utf-8"))
                plan = plan_install(report, profile, metadata)
        record = store.save_original(plan, report)
        preview = {"plan": plan.to_dict(), "plan_hash": plan.plan_hash,
                   "originals": record.snapshot_dict(), "record_hash": record.record_hash,
                   "confirmation_hash": plan.confirmation_hash(record),
                   "unresolved_facts": report.unresolved_facts,
                   "execution_available": plan.operation in {"debloat", "install"}}
        write_private_json(args.output, preview)
    except (AdbError, DiscoveryError, PolicyError, PlanError, RecordError, OSError,
            ValueError, TypeError) as error:
        print("error: preview refused: %s" % error, file=sys.stderr)
        return 1
    print(_summary(report))
    print(json.dumps(preview, indent=2, sort_keys=True))
    print("private preview: %s" % args.output)
    print("No remote mutations. Apply requires this saved preview and explicit --confirm.")
    return 0


def _apply(args, parser, runner):
    plan = None
    try:
        store = RecordStore(args.records)
        plan = store.load_plan(args.plan)
        adb_path = args.adb or os.environ.get("ADB") or shutil.which("adb")
        if runner is None and not adb_path:
            raise PlanError("adb not found; no mutation or success fallback")
        client = AdbClient(adb_path or "adb", args.serial, args.user, runner=runner)
        result = apply(plan, client, store, args.confirm)
    except (RecordError, PlanError, OSError, ValueError, TypeError) as error:
        result = ApplyResult("REFUSED", getattr(plan, "operation_id", ""),
                             (), (str(args.plan), str(args.records or RecordStore().root)), str(error))
    print(json.dumps(result.to_dict(), indent=2, sort_keys=True))
    return {"COMPLETED": 0, "REFUSED": 2, "PARTIAL": 3, "FAILED": 1}[result.status]


if __name__ == "__main__":
    sys.exit(main())
