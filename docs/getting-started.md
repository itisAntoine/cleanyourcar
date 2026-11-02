# Try CleanYourCar on your head unit

This guide is for a **parked trial of the launcher**, not debloat or a firmware upgrade.
Start with the [README](../README.md) for what is available and the current limitations.

## Before you start

- Your head unit must actually run Android 6.0+ (API 23+), not just project Android Auto
  or CarPlay from a phone. Some vendors advertise an Android version different from
  the real SDK level.
- Keep the original HOME app installed. Make sure you know how to open the unit's
  settings and return to its original home screen.
- Installation and HOME selection depend on the firmware. If the unit refuses an
  action, stop; this project does not provide a bypass.
- Use a computer with the build environment described in [development.md](development.md).
  There is no production-signed download promised by these instructions.

## 1. Build without contacting the car

From the cloned repository:

```sh
BUILD_ONLY=1 tools/build-and-install.sh
```

This builds the core launcher and refuses an APK of 524,288 bytes or more.
It does not use ADB, install anything, grant permissions or change HOME.

Find the result at `app/build/outputs/apk/release/app-release.apk`.
Despite the directory name, it is debug-signed and intended for local development.
Do not distribute it as a production release.

## 2. Install through Android's normal installer

Transfer the APK using a method your unit supports, such as removable storage, and open
it through the unit's file manager or installer. If Android asks for permission to install
from that source, use only the unit's normal permission controls and a source you trust.

Do not disable built-in packages, enable developer menus you do not understand, or uninstall
a conflicting app to force installation. Updates to an existing installation can have
signing and data-compatibility constraints; a refusal needs investigation, not a destructive
workaround.

The ADB install wrapper is not an alternative way around a refusal: no firmware currently
has the qualification required for its install plan.

## 3. Open the app and choose your apps

Open CleanYourCar from the existing launcher before changing the default HOME app.

- **Device profile:** treat a suggested profile as a candidate. A matching model name
  alone does not validate vendor behavior.
- **App shortcuts:** choose the navigation, music and other apps already installed on
  your unit. On an unknown device, no vendor app is automatically selected.
- **Plus Code reference:** choose a city reference, current location, or no reference.
  Current location requires local consent and Android permission. Full Plus Codes do
  not need a reference area; short codes do.
- **HOME:** you may skip it. The launcher remains an ordinary foreground app.
- **HUD:** unavailable on every current adapter; granting overlay permission does not
  make it supported.

Check the layout and app launches while parked. Media behavior and system shortcuts vary
with firmware; do not assume a button works merely because a profile lists the feature.

## 4. Decide whether to use it as HOME

Only after your checks, use the head unit's supported default-app/HOME settings if you
want CleanYourCar as the home screen. Menu names vary by vendor; there is no universal
command in this guide.

Keep the original launcher available. If HOME selection is blocked, continue using
CleanYourCar as an ordinary app rather than disabling the stock launcher.

## Stop using the launcher

Select the original HOME app using the unit's supported settings. If you want to remove
a manually installed CleanYourCar APK, first ensure the original home screen is working,
then use Android's normal app settings if the unit permits removal.

This is different from restoring a debloat operation. Future qualified tooling changes
must use their own private recovery records; see [adb-records.md](adb-records.md).
Never use a historical list to blindly enable packages.

## Optional Navigation Bridge

The bridge is a separate companion, not a prerequisite for the launcher. It receives
destinations via Bluetooth Low Energy (BLE) or a local network (LAN).

- Build it separately with `./gradlew :navbridge:assembleDebug`; output:
  `addons/nav-bridge/build/outputs/apk/debug/nav-bridge-debug.apk`.
- Installation and startup are separate user choices. The core build-only command
  does not install or start it.
- BLE and LAN reception are off by default. Pairing requires a token; never share it.
- LAN is unavailable on API 34+. BLE authorization is not a confidentiality guarantee,
  and a LAN token is not transport encryption.
- Stop reception when you no longer need it.

For the iOS Shortcut flow, see [ios-shortcut-setup.md](ios-shortcut-setup.md)
(currently in Vietnamese). Read [compatibility.md](compatibility.md) before enabling
any companion integration.

## Get help or report your result

Use [troubleshooting](TROUBLESHOOTING.md). Share the model, actual SDK, sanitized firmware
facts, expected behavior and what happened, not tokens, location or raw private logs.

If a coding assistant helps, give it [PROMPT.md](../PROMPT.md). It must ask before each
device operation; opening this repo is not permission to run ADB.

Successful checks on your unit are useful even if you do not write code:
[contribute a device profile](contributing-device-profile.md).
