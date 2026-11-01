# Private ADB records, previews and gated apply

The tooling provides **read-only discovery, local previews and separately gated
installation/debloat apply**. Production write-qualification registries remain
empty: no currently shipped firmware is authorized to write. Do not run the
legacy install/disable/restore scripts as an alternative
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
and `path`. A successful installation preview verifies these against an actual
local regular APK file: SHA-256 and `aapt2 dump badging` package/version/minSdk.
Caller-authored metadata alone is never sufficient. The normalized absolute APK
path and artifact digest are bound into the saved plan. The checks run again at
apply, immediately before installation and during readback.

Use an **existing** SDK `aapt2`: an explicit `$AAPT2` takes precedence (an invalid
explicit path refuses rather than falling back), followed by `$ANDROID_SDK_ROOT`,
`$ANDROID_HOME`, the repository's `local.properties` `sdk.dir`, conventional
macOS/Linux SDK locations and PATH. Only existing executable tools are selected;
numeric build-tools versions are searched newest first. No tools are downloaded
and neither Gradle nor a device is invoked to inspect metadata. Missing APK/tool,
timeout, nonzero tool exit, diagnostic stderr, malformed/ambiguous metadata or a
hash/metadata mismatch refuses. SDK compatibility uses the actual integer SDK,
not an Android marketing version.

Production qualification registries are intentionally empty. Neither the XYAuto
reference list nor an imported profile/policy qualifies writes. Debloat and
installation previews refuse unqualified firmware. Installation updates are
refused because compatible data-preserving rollback has not been established.
Optional companion installation is a separate qualified operation.

Successful previews include target/firmware hashes, Android user, profile/list/
artifact hashes, ordered typed actions, exact originals, unresolved facts, risk
notes, a plan hash, and a confirmation hash binding the plan to its recovery
snapshot. A hash is not user authorization. A saved preview never executes itself.

## Explicit apply and result contract

Command form (documentation only; running it contacts and may mutate the
explicitly selected device):

```text
python3 tools/car.py apply --serial <transport> --user <id> \
  --records <same-private-root> --plan <private-directory/plan.json> \
  --confirm <exact-confirmation-hash>
```

All four target/approval arguments are required; no implicit device selection,
interactive approval default or automatic install-on-preview exists.
`RecordStore.load_plan(path)` reads a private preview and compares its complete
plan, originals, record hash and confirmation with `RecordStore.load`'s anchored,
durably revalidated snapshot/aggregate. Editing preview originals cannot replace
record truth. `apply(plan, client, store, confirmation_hash)` holds the record
root and target locks throughout the complete batch, including nested load/append
calls. Use one root consistently; separate roots cannot share this exclusion.

New previews include `preconditions.write_qualification`: the complete profile
and (for debloat) policy content, bound by the plan/snapshot/confirmation hashes.
The executor revalidates content against the production release-trust registries
and the current report before **every** command, including after durable intent
persistence. The profile, policy, candidate actions and artifact must still bind
their exact hashes and qualified firmware. Imported evidence strings cannot grant
qualification. Older previews without proof must be regenerated, not upgraded
or trusted by the executor.

For each action, apply re-loads anchored originals, verifies confirmation, reads
fresh identity/firmware/user, HOME and relevant installed identities/exact enabled
flags, and checks all remaining preconditions plus previously confirmed actions.
The plan's before facts must match the anchored earliest originals; a new preview
cannot silently treat independently changed states as new originals. A durable
intent is appended before the command, then originals, proof, identity and state
are rechecked. Identity is checked again immediately before dispatch and during
readback. A mismatch, unresolved fact, persistence failure or unexpected state
stops the batch; there is no retry, reconnect command or destructive alternative.

Debloat permits only `pm disable-user --user <id> <package>`, one package per
action. Exact per-user readback must show flag `3`, with unchanged package identity
and other preconditions. Another user's state does not establish success.
Installation permits only `adb -s <serial> install --user <id> <verified-local-apk>`,
with no update/downgrade/permission flags. It requires a recorded absent original
and fresh absence in the global package dump, including other users and hidden
system copies: per-user absence alone is not signing/update compatibility. Any
existing package, unavailable global absence or unverified rollback refuses;
there is no conflicting-package uninstall. Readback must show the exact package,
version and an enabled flag (`0` or `1`) for the selected user.

Zero command exit alone never establishes success: recognized package-manager
output, fresh exact readback and a durable confirmed journal result are required.
Installation recognizes `Success`, optionally preceded by the fixed ADB
`Performing Streamed Install` or `Performing Push Install` progress line; arbitrary
output, failure messages and progress without verified readback do not confirm.
There are no bridge receivers, HOME, permissions, accessibility, location,
component launch, factory/camera, reboot or other lifecycle commands. Foreground
launch and camera/vehicle behavior require separate explicitly authorized parked
on-device validation; package-manager completion is not a functional/HUD safety
claim. Physical qualification remains a release gate, not a mocked-test claim.

`ApplyResult` and CLI JSON contain an operation ID, **every planned action** with
expected/observed before/after (unknown/unattempted observations remain `null`),
attempted/confirmed flags, snapshot/intent/readback recovery references, an error
and recovery instructions:

| Status | Meaning | CLI exit |
| --- | --- | --- |
| `COMPLETED` | All selected actions verified and durably recorded | `0` |
| `REFUSED` | No command dispatched; a gate failed (an intent can remain) | `2` |
| `FAILED` | A command was attempted but no action was verified successful | `1` |
| `PARTIAL` | A verified action exists but the batch/result durability did not complete | `3` |

An observed successful mutation whose result cannot be persisted is `PARTIAL`,
not `COMPLETED`; its durable intent is the recovery boundary. Any existing journal
history, including a failed action, pending intent or torn tail, refuses replay.
Inspect the records and target while parked; full restoration and separately
confirmed removal of a newly installed APK belong to Task 5. Restore previews
remain available, but Task 4 does not execute restore/settings actions.

These are sequential checks, not an atomic transaction with Android. A transport
replacement or external state/artifact change in the final read-to-command gap
cannot be prevented atomically by ADB. Fixed non-update commands, repeated identity
and byte checks, and stop-on-unexpected-readback bound that risk; no stronger
device-side transaction or hostile-local-owner guarantee is claimed.

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

### Original-state integrity

Each snapshot and aggregate has a separate private `.integrity.json` sidecar
containing the SHA-256 of its **complete canonical saved content**. The digest is
persisted when the original content is saved, not generated from whatever is on
disk during loading. Loading, retrying and extending verify these anchors first.
Missing, malformed or mismatched anchors refuse; the tooling does not regenerate
them from current device observations or automatically upgrade unanchored older
records. The snapshot anchor is immutable; the aggregate anchor changes only
after a validated extension that preserves every previously saved original.

Every loaded snapshot must also agree with its anchored target/user aggregate
on HOME and its individual package flags, settings and installed identities.
The complete required key sets are derived from the typed plan actions, not from
whatever entries remain in the snapshot. Each package action requires its
original installation identity; each package-state action requires its exact
original flag; an APK action also requires a flag when originally installed.
Known original absence requires an explicit identity entry with `null` and no
invented flag. Every settings action requires its individual original key, even
when the original is explicitly `null`. Omitting required entries refuses even
if the snapshot alone is reanchored while the independent aggregate is unchanged.
Before saving an extension, existing snapshots are checked as independent
original truth. Editing an aggregate and its sidecar cannot silently change
originals already held in a separately anchored snapshot. Deleting the aggregate
and sidecar while snapshots remain does not permit reconstructing new originals.
`record_hash` is useful for confirmation binding only **after** these checks;
a recomputed hash by itself is not proof of original-state integrity.

Data and sidecar are individually atomic/durable, but their two replacements
are **not a multi-file atomic transaction**. Data is persisted before its anchor.
An interruption between the replacements leaves complete files with a missing
or mismatched anchor and blocks load/retry/extension, including prior operations
that depend on that aggregate. Old snapshot files/anchors and complete aggregate
content remain available for explicit manual investigation; there is no silent
repair, success inference, or write authorization. An interruption before
replacing existing data leaves its previously valid pair usable. Keep the whole
private record tree together when backing it up.

If final directory `fsync` fails **after** anchor replacement, the visible pair
may nevertheless match. Matching digests are not a durability receipt. Every
anchored read explicitly `fsync`s both saved data and anchor files and their
containing directory before returning trusted data. Loading a record revalidates
both its snapshot pair and aggregate pair. Any failed revalidation refuses;
a later successful revalidation can establish durability for a complete matching
pair without editing content or regenerating anchors. Missing or mismatched
pairs remain unavailable and are never repaired by this check.

These checks detect edits/corruption relative to retained independent anchors.
They are **not tamper-proof authentication against the local owner**, who can
replace all relevant snapshots, aggregates, sidecars, backups or trusted source
code. Coordinated replacement/rollback of all original-truth anchors is outside
the guarantee. No remote trust service, signing key or hardware qualification
is implied.

Locks use nonblocking `flock` and are held by `with store.lock(plan.target):`
through an entire executor batch. Save/load/append also lock internally;
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
before any remote command. Readback requires an unmatched intent;
`confirmed: true` requires the requested observed state, not merely a successful
shell exit. Failed observations use `confirmed: false` and remain visible.
Entries carry sequence numbers and a hash chain; snapshots are never rewritten
to pretend commands succeeded. Every journal read also re-establishes file and
containing-directory `fsync` durability before trusting visible bytes. A failed
append can leave visible content; visibility alone is not a durability receipt.

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
Task 5.

`RecoveryRecord.export_summary()` is an allowlist containing only operation type,
counts, torn status, and constant limitations. It excludes target identifiers and
hashes, package selections, HOME and settings. Private snapshots/previews are
not sanitized exports; inspect and approve additional data before sharing.

## Offline verification

```text
python3 -m unittest discover -s tools/tests -p test_records.py -v
python3 -m unittest discover -s tools/tests -p test_operations.py -v
python3 -m unittest discover -s tools/tests -v
```

Tests use synthetic reports/transports, fixture APK bytes and mocked subprocess
`aapt2` output. Set `TMPDIR` to a Git-ignored scratch directory inside the worktree
when running existing record tests. No real ADB, emulator, device, aapt2 execution
or network scan is needed; no firmware qualification is established by these tests.
