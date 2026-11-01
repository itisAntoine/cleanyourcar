# Compatibility

CleanYourCar runs as an ordinary Android app on aftermarket head units and on Android
Automotive OS (AAOS). What it can do on a particular unit depends on that unit's
firmware and on checks made on that unit. A device profile, a forum report or a matching
model name is never enough on its own.

This page lists the support labels, the platform limits, and the qualification work that
is still open. It describes the current development branch, which has not been released.

## Support badges

Each badge describes one feature, so a device can carry some badges and not others. For
example, a unit can be Media-ready without being HOME-ready.

| Badge | Meaning |
|---|---|
| **Basic** | Foreground dashboard, app shortcuts for the roles you chose, app library, preferences, clock and supported navigation intents. Every Android 6.0+ (API 23+) device is expected to reach Basic. |
| **HOME-ready** | Basic, plus the launcher was set as the default HOME app and returning to HOME was checked. |
| **Media-ready** | The media controls and metadata passed on that unit. Subfeatures that did not pass stay hidden. |
| **HUD-ready** | Every overlay, reverse-yield, map-visibility, lifecycle and recovery check passed on that unit. **No current device or adapter is HUD-ready** (see [HUD](#hud-and-reverse-camera)). |

Inside the app, a capability counts as *ready* only when all of these hold:

- it is available;
- it was validated locally on this device;
- nothing blocks it (permission, system restriction, missing app, changed environment);
- the user consented, where consent applies.

Validation is tied to the firmware, the adapter revision, the safety-profile revision, the
chosen app and the permissions it depends on. If any of these changes, the capability goes
back to untested.

## Evidence states

Every profile in [`profiles/devices/`](../profiles/devices) carries an
`evidence.classification`:

| State | Meaning |
|---|---|
| `reported` | Facts collected by a device owner and sanitized. Nothing has been tested by the project. Every new profile starts here. |
| `device-tested` | The listed capabilities were exercised on a physical unit with that firmware, and the results are recorded with the profile. |
| `maintainer-reviewed` | A maintainer reviewed the evidence and accepted the labels it supports. |

None of these states activates a feature by itself:

- A profile at any evidence state only supplies defaults and claims.
- The launcher still checks capabilities on the user's own device.
- No profile turns on the HUD.
- No profile allows a debloat run.

The bundled XYAuto YT3518C profile is classified `reported`.

## Unknown devices

On a device that matches no profile, or that matches more than one, the launcher runs in
generic mode:

- **First run:** setup opens. Every step is optional, and refusing HOME leaves a working
  foreground app.
- **Roles:**
  - no vendor app is pre-selected;
  - each role stays empty until you choose an app;
  - the navigation app must be chosen explicitly.
- **Media:** the generic adapter uses standard Android media keys and sends no vendor
  broadcasts. Metadata and source selection are shown only when they actually work.
- **HUD:** stays off.
- **ADB tooling:** only read-only discovery is permitted; no writes. The tooling itself is
  tested offline, but no firmware has physical write qualification.

Matching uses the real SDK level and the build facts, not the advertised Android version.
Some units report a fake `ro.build.version.release`; the XYAuto reference unit reports
"8.0" but runs API 23.

## Android Automotive OS (AAOS)

AAOS support is limited to **parked use of a foreground app**:

- **No distraction-optimization claim.** The app declares no activity as
  distraction-optimized and claims no safety certification.
- **No bypass of system restrictions.** When the system blocks a screen or an action, for
  example a driving restriction or a refused settings panel, the launcher reports it
  ("The car system is blocking this screen (for example while driving). Try again when parked.") and does not try another route.
  - App roles stop at the first refused route. They do not fall back to another component,
    to the package launcher, or to a settings page.
  - Refused settings panels are explained rather than replaced by the main settings
    screen.
  - This no-retry rule applies **only when the detected platform is AAOS**. On aftermarket
    and other platforms a refused launch usually means a non-exported or protected
    component, not a driving restriction, so the launcher keeps its fallback chain
    (equalizer action, component, package; Bluetooth or other panels, then main settings)
    and reports only "The chosen app could not be opened." when every route fails.
  - Real AAOS UX restrictions usually do not throw at launch: the system starts the
    activity and then swaps in its `ActivityBlockingActivity`. The refusal message covers
    only the launches the system rejects outright.
- **No vehicle claims.** Being able to install on AAOS does not prove access to HOME,
  overlays or vehicle data.
- **HUD:** the HUD is never offered in AAOS mode.
- **Navigation bridge:** the system may reject the companion entirely. The launcher works
  without it.
- **OEM status:** no OEM system has been tested yet (see [Qualification gaps](#qualification-gaps)).

## Parked-only setup

These operations are meant to be done while parked:

- setup;
- text entry (for example the Plus Code keypad);
- profile import and changes;
- debloat and restore;
- device validation.

Aftermarket firmware usually exposes no trustworthy "vehicle is moving" signal, so the
launcher does not claim to enforce parked-only use. On AAOS, the operating system's own
driving restrictions apply and are respected.

## HUD and reverse camera

The floating HUD (music panel and pill over the map) is **paused on every current adapter**.

- **Generic adapter:** reverse support is `NONE`.
- **XYAuto adapter:** forwards reverse broadcasts (`UNVERIFIED_EVENTS`). No verified way
  exists to read the initial reverse state or to resynchronize it, so the HUD stays
  unavailable rather than guessing.
- **Requirements before the HUD can return**, all on the same unit:
  - local HUD validation;
  - a verified map layout;
  - an adapter whose reverse support is verified.
- **When a session ends:**
  - The events that end a session are:
    - reverse entered or exited;
    - integration failure;
    - a revoked overlay permission;
    - a firmware, adapter or safety-profile change;
    - a denied HUD request.
  - Each one removes the overlay windows, dismisses the overlay keypad and stops the
    service.
  - Nothing reopens automatically; the user has to start the HUD again.
- **Permission revocation:** revoking "display over other apps" is detected at the next
  HUD interaction. The OS itself removes the windows of an app that lost the permission.

## Navigation bridge (`:navbridge`)

The bridge is a separate, optional APK. The core launcher declares no `INTERNET` permission;
only the bridge does, for local-network reception.

- **Off by default.** BLE and LAN reception are enabled separately. Automatic start needs
  its own consent.
- **Tokens:** every request must carry the locally paired client token. Unauthenticated
  requests are rejected.
- **LAN on Android 14+ (API 34+):** refused. No foreground-service type legitimately covers
  an inbound LAN listener there, so the bridge reports `LAN_LIFECYCLE_UNAVAILABLE` and does
  not start the listener. BLE reception may still be available.
- **Errors:** start and stop errors are shown to the user and never reported as successful
  reception.

## Privacy review

**The core launcher (`:app`):**

- **Network:** no `INTERNET` permission, so the core launcher cannot reach the network.
- **Stored on the device (app-private storage):**
  - `launcher_configuration.json`, which holds:
    - role choices;
    - the accepted profile ID, revision and adapter;
    - the app-visibility mode;
    - local validation keys.
  - The existing `carplay_launcher_prefs` preferences, which are read but never rewritten
    by the migration. They hold:
    - UI preferences (theme, language, dock side, columns, app order and hidden apps);
    - the chosen city preset for Plus Code references.
- **Not stored:**
  - **Location:** a "current location" reference uses a fresh fix each time and is not
    saved.
  - **Backup:** `allowBackup` is `false`, so none of this data is included in Android
    backups.
- **Exported:** only when the user explicitly exports, a sanitized copy containing:
  - the accepted profile ID, revision and adapter;
  - role overrides;
  - app visibility;
  - UI preferences;
  - the navigation reference mode (`CITY`, `CURRENT_LOCATION` or `NONE`).

  The export leaves out validation keys, version identities, profile evidence, the saved
  city selection, coordinates, location consent and unknown legacy data. The mode is a
  preference only; exporting `CURRENT_LOCATION` does not export a location or permission.
- **Imported profiles:**
  - They are data only: at most 64 KiB, validated by the app's profile codec (rules mirror
    [`profiles/schema.json`](../profiles/schema.json)).
  - They are shown as a preview and applied only on confirmation.
  - A failed write keeps the previous configuration.
- **Imported settings:** Older sanitized exports without a reference mode use `CITY`.
  Importing `CURRENT_LOCATION` stores that preference but clears this device's local location
  consent, so current-location navigation remains disabled until the user grants permission
  and confirms it locally. City names and location data are never transferred.

**The navigation bridge:**

- **Token storage:** the pairing token stays in the bridge's private preferences. Only its
  digest is used for comparison.
- **Destinations:** they are passed to the launcher and not kept.

**Device profiles:**

- Profiles must not contain serial numbers, network addresses, account names, coordinates
  or personal media.

## Changes when upgrading from the XYAuto-only version

- **HUD paused.** Existing users see a one-time notice explaining it.
- **Navigation bridge** no longer starts automatically. Re-enable it explicitly in the
  bridge settings.
  - Each receiver (BLE, LAN) must be enabled separately.
  - Pairing generates a token.
- **iOS Shortcuts** must be re-created with the new token. Shortcuts made for the old
  unauthenticated listener are rejected. See [ios-shortcut-setup.md](ios-shortcut-setup.md)
  (in Vietnamese).
- **Navigation app.** On new generic installs the navigation app must be chosen explicitly,
  and Google Maps is not assumed. Apps that were chosen explicitly in the previous version
  are migrated.
- **Plus Code reference.** There is no implicit Ho Chi Minh City reference any more:
  - **Full codes:** need no reference.
  - **Short codes:** need either a city you pick explicitly or a fresh current location.
  - **Existing installs:** a city chosen earlier is kept.

## Qualification gaps

Qualification of this branch so far:

- **Compiled only:** the instrumentation tests in `app/src/androidTest`
  (`SetupCompatibilityTest`, `AdaptiveLayoutTest`, `HudLifecycleTest`) are compiled in the
  build. They have **not yet been run** on any device or emulator.
- **Host size for `AdaptiveLayoutTest`:** each layout fixture is simulated inside the test
  activity's window, so the host window must be at least as large as the fixture (at least
  1920x1280 px, for example a 2560x1600 tablet AVD). On a smaller host the fixtures that do
  not fit are skipped and the test reports them as an assumption failure, not a pass.
- **Unit tests:** JVM unit tests cover the policy logic.

Before the matching claims can be made, the following is still needed:

| Claim | Missing evidence |
|---|---|
| Any device, including the emulator | A first run of the instrumentation suite (`./gradlew :app:connectedDebugAndroidTest`). |
| XYAuto YT3518C vendor features | Physical validation on the unit:<ul><li>HOME return;</li><li>media routes;</li><li>reboot and ACC resume;</li><li>camera priority;</li><li>recovery;</li><li>power cycles;</li><li>the equalizer fallback chain (`xyaps` action, component, package).</li></ul> |
| Vendor-independent ("generic") media support | At least one materially different aftermarket firmware tested on real hardware. |
| Any named AAOS OEM system | At least one physical, eligible AAOS system. Until then, the claim is limited to emulator-tested generic mode, and that run has not happened either. |
| Dialog bounds under API 35 edge-to-edge and on a real rotation | A device run of `AdaptiveLayoutTest`. |
| HUD-ready on any unit | An adapter with verified reverse support, a verified map layout, and measured removal latency. |
| ADB install, debloat and restore | The forthcoming ADB tooling, plus a controlled fixture and an authorized matching device. |

## Release builds

For local testing only:

- The `release` build type is signed with the debug key, so it is a development artifact.
- Do not distribute it as a production release.
