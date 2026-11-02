# Build and develop CleanYourCar

For a user-facing introduction, start with the [README](../README.md) and
[getting started](getting-started.md). For contribution boundaries, read
[AGENTS.md](../AGENTS.md).

## Build environment

- Git and a configured Android SDK.
- Android SDK platform 37, as required by both modules' `compileSdk = 37`.
- The repository's Gradle 9.6.0 wrapper and Android Gradle Plugin 9.4.1.
- A Java installation capable of starting the wrapper. The daemon toolchain is pinned
  to Java 25 and resolved through Foojay; first use may download it and build dependencies.
- Application and test sources are Java-only, compiled for Java 11.
- Python 3.9+ for documentation and host-tool tests, with standard-library dependencies
  only. Host tooling targets macOS/Linux; Windows remains unqualified.

Set the Android SDK location through your normal Android environment configuration or
an uncommitted `local.properties`. Read the actual build files before changing SDK or
toolchain versions; a newer advertised Android release is not a device-compatibility test.

## Modules and directories

| Path | Purpose |
|---|---|
| `app/` (`:app`) | Core launcher, package `com.cleanyourcar.launcher`; no `INTERNET` permission. |
| `addons/nav-bridge/` (`:navbridge`) | Optional destination receiver, package `com.cleanyourcar.navbridge`. |
| `profiles/` | Data-only schema, candidate device profiles and shared test fixtures. |
| `examples/` | Reported device notes and historical, unqualified debloat candidates. |
| `tools/` | Python ADB tooling and plan-only shell entry points. |
| `docs/` | User, compatibility, development and safety documentation. |
| `gradle/libs.versions.toml` | Dependency and plugin version catalog. |

## Build and validate

Run from the repository root:

```sh
./gradlew :app:assembleDebug :navbridge:assembleDebug
./gradlew :app:assembleRelease :navbridge:assembleRelease
./gradlew :app:testDebugUnitTest :navbridge:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebugAndroidTest
python3 -m unittest discover -s tools/tests -v
```

Debug APKs live in each module's `build/outputs/apk/debug/` directory; release APKs
live in `build/outputs/apk/release/`.

For the core size gate:

```sh
BUILD_ONLY=1 tools/build-and-install.sh
```

The core must be **below 524,288 bytes**; an equal or larger APK is rejected.
The bridge has a separate APK and is not included in this budget. Build-only does
not contact a head unit.

`assembleDebugAndroidTest` compiles instrumentation only. After explicit authorization
for a connected emulator or device, the run command is:

```sh
./gradlew :app:connectedDebugAndroidTest
```

Instrumentation has not yet been run as part of this project's current qualification.
Do not describe a successful compile or offline fixture test as a real-device result.

## Signing and distribution

Both modules' `release` build types currently use the Android debug signing key.
The artifacts are for local testing, not production distribution or store publication.

No production signing key is included. A real distribution needs its own signing
configuration outside Git, a deliberate update-compatibility policy, and the relevant
hardware validation. Never commit keys or prebuilt APKs.

## If a build fails

- **SDK not found:** check your SDK path and the required installed platform.
- **Toolchain cannot be resolved:** check the Java launcher and access to the configured
  toolchain/dependency repositories. Do not change source compatibility to disguise
  a missing build prerequisite.
- **APK size rejected:** investigate the size increase; do not disable the gate.
- **Device installation refused:** that is not a build error. Stop and inspect firmware,
  existing package/signing compatibility and the applicable qualification limits.

See [troubleshooting](TROUBLESHOOTING.md) and [compatibility](compatibility.md) for
device-specific issues. For host operation details, use [adb-workflow.md](adb-workflow.md)
and [adb-records.md](adb-records.md).
