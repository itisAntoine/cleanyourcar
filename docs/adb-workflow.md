# ADB workflow

Host tooling (`tools/car.py` and three thin wrappers) discovers a head unit, writes
private previews, and — only after separate explicit confirmation — applies a saved
preview. It needs **Python 3.9+** (standard library only) on macOS or Linux. Windows
is unqualified until its subprocess and durability tests pass; do not copy the older
PowerShell scripts.

## Release gate: physical qualification is pending

Write support requires qualification on explicitly authorized, **parked** reference
hardware with verified camera/vehicle baselines and a recovery path. That step has
**not been done**. All production qualification registries are empty, so no firmware is
write-supported: debloat and install previews refuse, and no fixture, evidence or
profile in this repository claims otherwise. AAOS stays discovery-only even when the
foreground APK works. Package signing/update constraints and denied OEM writes are
release gates, not failures to bypass.

## Rules

- Select exactly one device with `--serial <transport>` and one Android user with
  `--user <id>`. Neither is ever guessed; an IP address alone does not identify a unit.
  `CAR_HOST` is no longer read.
- Discovery is read-only. Setup, review and any future write are parked-only.
- Every write is planned, target-bound, snapshot-first, confirmed by you, journaled and
  read back. Wrappers only create plans; they never confirm or apply.
- Restore returns recorded settings/package states only. It is not a firmware or
  app-data backup, and it does not blindly re-enable a list.
- Records and previews are private (directory mode `0700`, files `0600`). Keep them out
  of Git and shared profiles. See [adb-records.md](adb-records.md).
- Core HOME choice, permissions, accessibility and bridge services stay user-directed.
  The tooling does not grant permissions, change settings, start services or disable
  the stock launcher.

## Commands

Forms below contact the selected device when run, except where marked static.

```text
python3 tools/car.py discover --serial <transport> --user <id> --output <private/report.json>

tools/disable.sh <list.txt> --serial <transport> --user <id> --profile <profile.json> \
  --policy <policy.json> --output <private/plan.json>
tools/disable.sh <list.txt> --dry-run     # static only: no device is contacted

tools/restore.sh <operation-id> --serial <transport> --user <id> --output <private/plan.json>

BUILD_ONLY=1 tools/build-and-install.sh   # build + size gate; no ADB, no plan
tools/build-and-install.sh --serial <transport> --user <id> --profile <profile.json> \
  --output <private/plan.json> [--with-bridge]

python3 tools/car.py apply --serial <transport> --user <id> --plan <private/plan.json> \
  --confirm <exact hash>      # separate step, after you review the preview
```

- `--dry-run` checks list syntax and package protections only. It is **not** a device
  safety or qualification result.
- `restore.sh` accepts a recorded operation ID. Passing a debloat list or record file is
  refused.
- `build-and-install.sh` builds `:app` and enforces the 524,288-byte core APK bound
  (a size of 524,288 or more fails), then writes an install plan. The Navigation Bridge
  is off by default; `--with-bridge` builds it and plans a separate install. Nothing is
  installed by the wrapper.

## Sharing a device profile

```text
python3 tools/car.py export-profile --report <private/report.json> --output <candidate.json>
```

The output is a sanitized, valid-schema candidate classified `reported` only: public
build properties and SDK, no identity hashes, serials, addresses, accounts, package
inventories, roles or protections. It is never verified. Review every field by hand
before sharing; see [contributing-device-profile.md](contributing-device-profile.md).
