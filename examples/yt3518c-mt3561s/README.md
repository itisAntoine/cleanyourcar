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
The legacy commands below do not establish qualification.

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

## Quick Start Over Wi-Fi ADB

```bash
# 1. Disable bloatware (frees ~550 MB RAM while keeping Reverse Camera, Bluetooth, USB Music, Dashcam, Equalizer, and Google Maps)
CAR_HOST=192.168.100.234 ./tools/disable.sh examples/yt3518c-mt3561s/debloat.txt

# 2. Build and install CleanYourCar (:app) + Navigation Bridge (:navbridge)
CAR_HOST=192.168.100.234 ./tools/build-and-install.sh

# 3. Restore everything if ever needed
CAR_HOST=192.168.100.234 ./tools/restore.sh examples/yt3518c-mt3561s/debloat.txt
```
