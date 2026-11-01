# CleanYourCar

**A lightweight home screen for Android car head units.**

Put your navigation, music, radio and other apps within easy reach, with a dashboard,
an app library and shortcuts you choose yourself. CleanYourCar started on an XYAuto
YT3518C unit and is being adapted for other Android head units.

**This is an early development project, not a ready-for-every-car installer.**
You can build the launcher and try it as an ordinary app while parked. The floating
HUD is unavailable on all current adapters, and automated ADB installation and debloat
are blocked until physical-device qualification is completed.

[Start here](#start-here) · [What you get](#what-you-get) ·
[Will it work on my screen?](#will-it-work-on-my-screen) ·
[Try the launcher](#try-the-launcher) · [Questions](#questions) ·
[Build and contribute](#build-and-contribute)

## Start here

| You want to... | Start with... |
|---|---|
| Understand what this project does | [What you get](#what-you-get). No Android development knowledge needed. |
| Try it on your Android head unit | [Try the launcher](#try-the-launcher), then the [step-by-step guide](docs/getting-started.md). |
| Check your model or firmware | [Compatibility](docs/compatibility.md) and the [XYAuto reference notes](docs/devices/yt3518c-mt3561s.md). A similar model name is not proof of support. |
| Get help from a coding assistant | Open this repository in the assistant and use [PROMPT.md](PROMPT.md). It must ask before contacting your device. |
| Remove unwanted preinstalled apps | **Not available yet through this project's tooling.** Read the [ADB workflow](docs/adb-workflow.md); do not reuse a historical list as a safe recipe. |
| Help support another head unit | [Contribute a device profile](docs/contributing-device-profile.md), or report what worked and what did not. |

## What you get

| Feature | What it does |
|---|---|
| **Dashboard** | Shows the clock and shortcuts for navigation, Bluetooth music, local music, radio, dashcam, equalizer and settings. Choose the apps installed on your unit. |
| **App library** | Lets you browse and open your installed apps. |
| **Navigation entry** | Lets you enter a Plus Code and send the destination to your chosen navigation app. Short codes need a reference area; location use requires your consent. |
| **Adaptive layout** | Adjusts to landscape, portrait and narrower windows. Actual hardware behavior still needs testing. |
| **Device profiles** | Suggest model-specific defaults where facts match. A profile is information, not permission to enable unsafe or untested features. |
| **Optional Navigation Bridge** | Receives destinations over Bluetooth Low Energy or a local network using a separate companion app. It is off by default and needs a pairing token. |

The launcher does **not** replace your car's operating system, add Android to a
non-Android screen, or guarantee that vendor media controls and reverse-camera behavior
will work. The core app declares no `INTERNET` permission; networking belongs to the
optional companion.

## Will it work on my screen?

The launcher targets **Android 6.0 or newer (API 23+)**. Meeting that minimum does not
mean every feature is supported.

| Your screen | Current expectation |
|---|---|
| Android aftermarket head unit | The foreground launcher is the starting point. Test your chosen apps and layout locally before considering it as your home screen. |
| XYAuto YT3518C | The project's original reference model. Its bundled profile is **reported**, not physically qualified for all features. |
| Android Automotive OS (AAOS) | Parked use as a foreground app only. System driving restrictions are not bypassed. |
| Android Auto projection, CarPlay, or a non-Android factory screen | Not a supported installation target. Android Automotive OS and Android Auto are different products. |

**Current limits:** no device is HUD-ready; no firmware is qualified for ADB writes.
Instrumentation tests have been compiled but have not been run on a device or emulator.
See [compatibility and remaining checks](docs/compatibility.md) for the full picture.

## Try the launcher

**Park first. Keep the original launcher installed and available.**

1. **Build the app on a computer.** Clone this repository and use the build instructions
   below. This README does not promise a downloadable, production-signed release.
2. **Transfer the APK and install it through Android's normal installer**, if your head
   unit permits it. Do not bypass a refused installation or uninstall another app to
   make it work.
3. **Open CleanYourCar as an ordinary app.** Follow the optional setup steps and choose
   your navigation and media apps. You can skip HOME selection and still use the app.
4. **Check the basics while parked.** Try opening your apps, check the layout, and make
   sure you can return to your original home screen. Only choose it as your default
   HOME app through the unit's supported settings after those checks.

The [getting-started guide](docs/getting-started.md) explains the APK location, setup,
optional bridge and how to stop using the launcher. Installing an APK manually is
separate from the currently blocked ADB install workflow.

## Questions

**Do I need ADB just to try the launcher?**

No. If your unit allows installation through Android's normal installer, you can try the
foreground app without ADB. ADB is Android's debugging connection; device discovery with
it is optional and still requires permission.

**Will this automatically remove apps or change my home screen?**

No. The shell wrappers create plans, never automatically apply them. Current production
write qualification is empty. HOME selection and permissions remain your choices.

**Can I use the floating HUD now?**

No. Current adapters do not provide the verified reverse-camera and overlay evidence
required to enable it. A profile or permission grant cannot override that decision.

**How do I go back?**

For a manual launcher trial, select the original HOME app in your unit's supported
settings. Do not disable it in the first place. Recovery from a future qualified ADB
operation uses its saved record and a separately confirmed plan, not a blanket
"enable every app" command. Records are not firmware or app-data backups.

**Is the Navigation Bridge required?**

No. The launcher works without it. The companion needs separate setup and a token;
LAN reception is unavailable on API 34+. Tokens authorize requests but do not encrypt
the connection. See [bridge setup](docs/getting-started.md#optional-navigation-bridge).

**Something is not working. Where do I start?**

Read [troubleshooting](docs/TROUBLESHOOTING.md), then open an issue with your sanitized
device/firmware details and the steps that failed. Never share tokens, VINs, device
identifiers, raw private logs or location. For vulnerabilities, use
[security reporting](SECURITY.md) rather than a public issue.

## Build and contribute

You need Git and a configured Android SDK to build. The Gradle wrapper uses AGP 9.4.1
and provisions a Java 25 daemon toolchain; application code targets Java 11.
SDK requirements and troubleshooting are in the [development guide](docs/development.md).

```sh
git clone https://github.com/itisAntoine/cleanyourcar.git
cd cleanyourcar
BUILD_ONLY=1 tools/build-and-install.sh
```

`BUILD_ONLY=1` builds the core APK and checks its size; it does **not** contact or install
anything on a device. The output is `app/build/outputs/apk/release/app-release.apk`.
Both modules' release builds currently use the Android debug key: they are local
development artifacts, not production releases for distribution.

For contributors, the main commands are:

```sh
python3 -m unittest discover -s tools/tests -v
./gradlew :app:assembleDebug :navbridge:assembleDebug
./gradlew :app:assembleRelease :navbridge:assembleRelease
./gradlew :app:testDebugUnitTest :navbridge:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
```

The last command **compiles** instrumented tests; it does not run them. Running tests on
a connected device or emulator is a separate, explicitly authorized operation.

Before changing code, read [AGENTS.md](AGENTS.md) for contribution boundaries and
[the development guide](docs/development.md) for the repo map and full build details.
You can also help without coding by [reporting a device profile](docs/contributing-device-profile.md).

## More information

- [Getting started](docs/getting-started.md): trying the app, setup and returning to the original launcher.
- [Compatibility](docs/compatibility.md): feature support, device evidence and firmware changes.
- [ADB workflow](docs/adb-workflow.md) and [recovery records](docs/adb-records.md): advanced, gated tooling.
- [Development](docs/development.md): modules, build requirements and validation.
- [Contribution rules](docs/DOS-AND-DONTS.md) and [security](SECURITY.md).

## License

See [LICENSE](LICENSE).
