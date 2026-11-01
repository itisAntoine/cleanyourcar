# Private ADB records and plan previews

The tooling currently provides **read-only discovery and local previews**, not an
executor. Do not run the legacy install/disable/restore scripts as an alternative
when a preview refuses an operation. Setup and review are parked-only activities.

## Preview commands

Every preview requires an explicit `--serial`, `--user`, and private `--output`.
The output's parent directory must be owned by the current user with mode `0700`.
Files are created with mode `0600`. Never commit or share these private files.

Command forms (documentation only; these commands contact the selected device
when actually run):

```text
python3 tools/car.py plan-debloat --serial <transport> --user <id> \
  --profile <profile.json> --policy <policy.json> --candidates <list.txt> \
  --output <private-directory/plan.json>

python3 tools/car.py plan-install --serial <transport> --user <id> \
  --profile <profile.json> --apk-metadata <metadata.json> \
  --output <private-directory/plan.json>

python3 tools/car.py plan-restore --serial <transport> --user <id> \
  --operation-id <source-operation-id> --output <private-directory/plan.json>
```

Use `--records <private-root>` consistently for all related operations; the
default is `~/.cleanyourcar/records/`. `--adb` selects the executable explicitly.
If keeping records inside a checkout, use its Git-ignored `.cleanyourcar/`
directory for both records and previews, not an arbitrary tracked directory.
`--app-version-code` supplies the profile compatibility version (default `1`).
Installation metadata has exactly `package`, `version_code`, `min_sdk`, `sha256`,
and `path`. Metadata is **not** evidence that the APK's manifest, signature,
update compatibility, or bytes have been checked; the future executor must
independently verify them.

Production qualification registries are intentionally empty. Neither the XYAuto
reference list nor an imported profile/policy qualifies writes. Debloat and
installation previews refuse unqualified firmware. Installation updates are
refused because compatible data-preserving rollback has not been established.
Optional companion installation is a separate qualified operation.

Successful previews include target/firmware hashes, Android user, profile/list/
artifact hashes, ordered typed actions, exact originals, unresolved facts, risk
notes, a plan hash, and a confirmation hash binding the plan to its recovery
snapshot. A hash is not user authorization. Task 3 has no `apply` command or
success-returning execution stub.

## Storage contract

`RecordStore.save_original(plan, state)` accepts a discovery report, or
`{"report": report, "settings": {"namespace/name": exact_value}}`. A settings
value of `None` means explicitly observed absence; an omitted key means unknown.
Only code-supported setting routes can be represented; none are qualified yet.
An affected installed package must have an exact per-user enabled flag (`0`–`4`).
An explicit `package_flags.<package>` gap always refuses, including on retries
with existing originals. Known absence before a new install is recorded as
absence, never converted into post-install "original" enabled flags.

Each operation has an immutable `snapshot.json`. A private target/user aggregate
under `.originals/` preserves earliest package flags, package identities, HOME
facts, and individual settings across different operation IDs and extensions.
Changing firmware under the same target/user cannot extend that aggregate.
Do not delete or edit aggregates to bypass recovery requirements.

Snapshots use an exclusive same-directory temporary file, flush, file `fsync`,
atomic replacement, and directory `fsync`. Failure refuses the operation; an
interruption leaves either the prior or new complete snapshot, not partial JSON.
An aggregate persisted before an interrupted operation snapshot is retained:
it cannot authorize a write but protects the earliest observation.

Locks use nonblocking `flock` and are held by `with store.lock(plan.target):`
through an entire future executor batch. Save/load/append also lock internally;
same-thread/process nesting on the same store and target is supported. Lock
files are never unlinked; descriptor closure releases locks after a crash.
The frozen discovery binding may include user-scoped Android ID facts, so a
conservative **record-root-wide guard** additionally serializes identity aliases
and all targets in that root. Concurrent operations for different physical
targets are therefore also refused. Separate record roots are not mutually
locked; operators must use one root consistently. Windows is unqualified.

Directories/files must remain `0700`/`0600`, current-user owned and unlinked.
User-controlled symlinks (including ancestors) are refused; root-owned OS
ancestor aliases such as macOS `/var` are allowed. Existing insecure permissions
are refused, not silently fixed.

## Journal and honest recovery

`append_event(operation_id, event)` accepts:

```json
{"kind":"intent","action_index":0}
{"kind":"readback","action_index":0,"observed":{"enabled_flag":3},"confirmed":true}
```

The intent references the immutable typed action and must be durably returned
before any future remote command. Readback requires an unmatched intent;
`confirmed: true` requires the requested observed state, not merely a successful
shell exit. Failed observations use `confirmed: false` and remain visible.
Entries carry sequence numbers and a hash chain; snapshots are never rewritten
to pretend commands succeeded.

Loading preserves valid entries before an unterminated final journal line and
reports `journal_torn`. An incomplete tail is never treated as a confirmed
command. Before appending, that tail is durably preserved in `events.torn`, then
the journal is truncated to its valid prefix and synced. Corrupt complete lines,
duplicate JSON keys, broken chains, and invalid action references refuse loading.

Restore previews include package-state actions only for confirmed readbacks with
exact originals and unchanged package identity/current observed state. Pending
intent, failed readback, and torn-tail limitations remain explicit. Originals
alone do not prove that anything changed. HOME, permissions, settings restoration,
APK removal, and application-data rollback are not guessed; full restoration is
Task 5, remote execution is Task 4.

`RecoveryRecord.export_summary()` is an allowlist containing only operation type,
counts, torn status, and constant limitations. It excludes target identifiers and
hashes, package selections, HOME and settings. Private snapshots/previews are
not sanitized exports; inspect and approve additional data before sharing.

## Offline verification

```text
python3 -m unittest discover -s tools/tests -p test_records.py -v
python3 -m unittest discover -s tools/tests -v
```

Tests use synthetic reports/transports and local temporary directories. No real
ADB, emulator, device or network scan is needed.
