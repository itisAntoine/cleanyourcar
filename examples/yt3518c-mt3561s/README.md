# XYAuto YT3518C (`mt3561s` / `evb3561sv_w_65_m0`)

- **Chipset / SoC:** MediaTek / AutoChips `mt3561s` (`arm64-v8a`)
- **Firmware:** `YT3518C_00008_V008`
- **OS:** Android 6.0 (API 23, displayed as `"8.0"` in `ro.build.version.release`)
- **Display:** `1024×600` @ `160 dpi` (`mdpi`)
- **RAM:** 2 GB

## Debloat qualification and trust

`debloat.txt` is a historical reference, not authorization for the multi-device tooling.
The bundled `debloat-policy.json` remains **unqualified**. Its calendar-provider and Play
Store dependency questions are unresolved; no device qualification is claimed here.
The commands below only create previews and do not establish qualification.

`load_policy()` validates structure only. `validate_debloat_policy()` requires a qualified
policy whose **complete content** is pinned in the repository-shipped
`_TRUSTED_QUALIFIED_POLICY_HASHES` registry in `tools/car_support/profiles.py`. That registry
is currently empty, so no policy authorizes debloat. Imported reviewer/evidence strings,
matching hashes, or a `qualified` status cannot grant trust. The production API accepts
neither caller trust flags nor caller-supplied registries.

To qualify a future policy, maintainers must verify the exact firmware, profile and ordered
candidate list on the target, resolve all dependency decisions, preserve core/vehicle/HOME
protections, and record actual reviewer and evidence references. After review, ship the
complete policy and its content pin together in a trusted repository release. Compute the
pin as SHA-256 of UTF-8 `json.dumps(policy, sort_keys=True, separators=(",", ":"),
ensure_ascii=True)`. All fields, including notes and nested qualification/protection
decisions, are covered; object key order and JSON whitespace are not. Any content change
requires renewed review and a new shipped pin. This is a release trust boundary, not
protection against modifying trusted source code.

Trust is necessary but not sufficient: runtime firmware/profile/list binding, package facts
and all safety checks must still pass. In particular, unresolved HOME enumeration denies
debloat even with a known preferred default. Current API 23 discovery does not enumerate
all usable HOME handlers and therefore cannot authorize debloat. Switching HOME remains
outside debloat.

## Workflow (plan only)

The wrappers never apply anything. Pick the device with `--serial` (a transport serial,
never an IP guess) and the Android user with `--user`; keep outputs in a private
directory (mode `0700`). Physical qualification is **pending**, so the production
registries are empty and these previews currently refuse this firmware.

```bash
# 1. Static list check only (no device contact; not a safety or qualification result)
./tools/disable.sh examples/yt3518c-mt3561s/debloat.txt --dry-run

# 2. Debloat preview (read-only discovery of the selected device)
./tools/disable.sh examples/yt3518c-mt3561s/debloat.txt --serial <transport> --user 0 \
  --profile profiles/devices/xyauto-yt3518c/profile.json \
  --policy examples/yt3518c-mt3561s/debloat-policy.json --output .cleanyourcar/plan.json

# 3. Build and size gate (no ADB); then an install preview. Bridge only with --with-bridge
BUILD_ONLY=1 ./tools/build-and-install.sh
./tools/build-and-install.sh --serial <transport> --user 0 \
  --profile profiles/devices/xyauto-yt3518c/profile.json --output .cleanyourcar/install.json

# 4. Restore preview from a recorded operation ID (never from a list)
./tools/restore.sh <operation-id> --serial <transport> --user 0 --output .cleanyourcar/restore.json
```

Applying is a separate `python3 tools/car.py apply ... --confirm <hash>` step you run
yourself after reviewing the preview. See [docs/adb-workflow.md](../../docs/adb-workflow.md).
