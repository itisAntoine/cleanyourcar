# Contributing a device profile

A device profile tells CleanYourCar what is known about one head-unit model and firmware:

- how to recognize the unit;
- which integration adapter applies;
- which apps are sensible role defaults;
- which packages must never be disabled.

A profile is data. It cannot run code, and it **never enables the HUD or a debloat run by
itself**. The launcher still checks every capability on the user's own unit before calling
it ready (see [compatibility.md](compatibility.md)).

## Where profiles live

| Path | Purpose |
|---|---|
| [`profiles/schema.json`](../profiles/schema.json) | JSON Schema (format version 1). Every profile must validate against it. |
| `profiles/devices/<profile-id>/profile.json` | Bundled profiles. Each directory is packaged as an app asset and offered as a candidate match. |
| [`profiles/fixtures/`](../profiles/fixtures) | Valid and deliberately invalid samples used by the unit and instrumentation tests. |

The bundled XYAuto YT3518C profile,
[`profiles/devices/xyauto-yt3518c/profile.json`](../profiles/devices/xyauto-yt3518c/profile.json),
is a complete example.

Key schema rules:

- **Required fields:**
  - `schemaVersion`
  - `profileId`
  - `revision`
  - `minAppVersionCode`
  - `platform`
  - `adapterId`
  - `match`
  - `roleDefaults`
  - `featureClaims`
  - `protectedPackages`
  - `evidence`
- **Unknown fields are rejected** (`additionalProperties: false`).
- **`platform`:** `aftermarket` or `aaos`.
- **`adapterId`:** must name an adapter that already exists in the app (`generic` or
  `xyauto-yt3518c`). A new vendor adapter is a code contribution, not a profile field.
- **`match`:**
  - uses the **real** SDK range (`sdkMin`/`sdkMax`) and exact `buildProperties` strings;
  - empty strings are invalid, not wildcards;
  - do not match on `ro.build.version.release`, because some firmware fakes it.
- **`roleDefaults`:**
  - strategies are restricted to a fixed list (`launch-package`, `launch-component`,
    `google-navigation`, `geo`, `xyauto-equalizer`);
  - `launch-component` needs a `componentName` whose package equals `packageName`.
- **`featureClaims`:**
  - only `unsupported` or `requires-local-check`;
  - a claim can hide a feature but can never mark it as verified.
- **Size:** a profile must not exceed 64 KiB.

## Evidence states

Set `evidence.classification` honestly:

- **`reported`:** you collected and sanitized the facts. Every new profile starts here.
- **`device-tested`:** the listed features were exercised on a physical unit with the
  stated firmware, and the results are written in `evidence.notes`. Emulator runs do not
  count; they only validate the core app.
- **`maintainer-reviewed`:** set only by a maintainer after reviewing the evidence.

A higher evidence state changes the labels that may be advertised. It does not switch any
feature on.

## What to collect (sanitized)

Collect these facts:

- **Platform:**
  - the real SDK level (`ro.build.version.sdk`);
  - the advertised Android version, if it differs;
  - aftermarket or AAOS.
- **Build properties used for matching:**
  - board;
  - product;
  - manufacturer;
  - display/firmware string;
  - fingerprint, when available.
- **Hardware:**
  - SoC family;
  - logical screen size and density (for example 1024x600 at 160 dpi).
- **Integration:**
  - the integration adapter that fits;
  - the vendor apps that serve each role (navigation, Bluetooth music, local music,
    radio, dashcam, equalizer, settings), each as package and component name.
- **Protected packages:** apps that must never be disabled, for example:
  - reverse camera;
  - Bluetooth and telephony;
  - CAN bus and MCU bridges;
  - brake check;
  - audio DSP;
  - input methods;
  - system UI;
  - firmware update.
- **Per-feature results:** what you tried, what happened, and the known limitations.
- **Reproduction steps:** sanitized, in case something failed.

**Never include:**

- serial numbers or IMEIs;
- MAC or IP addresses, SSIDs;
- Android IDs, advertising IDs or account names;
- GPS coordinates or addresses;
- contacts, call logs or personal media;
- raw `getprop` or `dumpsys` dumps.

Copy only the specific values listed above.

Read-only ADB discovery and sanitization tooling is planned but is not part of this
repository yet. Until it lands, collect the facts by hand from the unit's settings screens
or with standard read-only `adb shell getprop` queries, and copy only the needed values.

## Submitting

1. Create `profiles/devices/<profile-id>/profile.json`. Use lowercase IDs such as
   `vendor-model`, and set `revision` to `1`.
2. Validate the file against `profiles/schema.json`.
3. Run the unit tests (`./gradlew :app:testDebugUnitTest`). They exercise the profile
   codec against the schema rules.
4. If you have notes beyond `evidence.notes`, add `docs/devices/<profile-id>.md` and list it
   in `evidence.references`.
5. Open a pull request with the following:
   - the evidence state;
   - the firmware string;
   - a short summary of what was and was not tested.

Expect the review to ask for evidence before any feature badge (see
[compatibility.md](compatibility.md)) can be claimed for your unit.
