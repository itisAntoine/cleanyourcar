# Copilot Instructions for CleanYourCar

## Communication & Language Policy
- **Vietnamese**: Used for all conversations, explanations, and multiple-choice options with users. Responses must be clear, easy to understand, and as concise as possible while retaining all critical information.
- **English**: Used for all written code, comments, documentation, ADRs, commit messages, and workspace artifacts.
- **Choice format**: Every conversation response ends with concise `A / B / C / X` choices.

## Build, Test, and Lint Commands

Use the Gradle wrapper (`./gradlew`) from the repository root:

- **Assemble Debug APK:** `./gradlew :app:assembleDebug`
- **Assemble Release APK:** `./gradlew :app:assembleRelease`
- **Run Android Lint:** `./gradlew :app:lintDebug`
- **Run all local unit tests:** `./gradlew :app:testDebugUnitTest :navbridge:testDebugUnitTest`
- **Run a single unit test class:** `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.RoleRoutingTest"`
- **Run a single unit test method:** `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.RoleRoutingTest.restrictedLaunchIsReportedWithoutTryingAnotherRoute"`
- **Compile instrumented tests (no device needed):** `./gradlew :app:assembleDebugAndroidTest`
- **Run instrumented tests (requires connected device/emulator):** `./gradlew :app:connectedDebugAndroidTest`
- **Run a single instrumented test class:** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cleanyourcar.launcher.SetupCompatibilityTest`

> **Note:** `appcompat`, `material` and `espresso-core` are declared in `gradle/libs.versions.toml` but intentionally left commented out in `app/build.gradle.kts`. The app theme (`Theme.CleanYourCar`) inherits from the framework `android:Theme.Material.NoActionBar` and needs no support library. Only `junit` and `json` (unit tests) and `ext-junit` plus `androidx.test:runner` (instrumented tests) are enabled; do not add runtime UI dependencies.

## Architecture & Project Structure

- **Two Android Modules:** `settings.gradle.kts` includes `:app` (the launcher; namespace and `applicationId` `com.cleanyourcar.launcher`) and `:navbridge` (the optional navigation bridge companion at `addons/nav-bridge`, namespace `com.cleanyourcar.navbridge`). Only `:navbridge` may declare `INTERNET`; `:app` must not.
- **Toolchain & SDK Configuration:**
  - Gradle `9.6.0` with Android Gradle Plugin (AGP) `9.4.1` and Kotlin DSL (`.gradle.kts`).
  - Gradle Daemon JVM toolchain is pinned to Java 25 (`gradle/gradle-daemon-jvm.properties` via Foojay resolver), while app source/target compatibility is Java 11 (`compileSdk = 37`, `minSdk = 23`, `targetSdk = 37`).
  - Gradle configuration cache is enabled by default (`org.gradle.configuration-cache=true` in `gradle.properties`); any custom Gradle tasks or build logic must be configuration-cache compatible.
- **Current State:** Working multi-device launcher. Sources are in `app/src/main/java/com/cleanyourcar/launcher` (with `compat`, `config`, `integration` and `layout` subpackages). Device profiles are in `profiles/` (schema, bundled devices, test fixtures). Compatibility and contribution docs are in `docs/`.

## Key Conventions

- **Java-Only Application Code:** Write all application, test, and instrumentation code strictly in **Java** (Java 11 compatibility). Do not introduce Kotlin source files (`.kt`) for app or test logic; Kotlin DSL (`.gradle.kts`) is used only for Gradle build scripts.
- **Dependency Management:** Always declare dependencies and versions in `gradle/libs.versions.toml` and reference them via the `libs` version catalog in `app/build.gradle.kts`. Repositories are strictly managed in `settings.gradle.kts` (`RepositoriesMode.FAIL_ON_PROJECT_REPOS`); do not add `repositories {}` blocks to module build files.
- **ADB Tooling:** Host tooling is Python 3.9+ stdlib (`tools/car.py`, `tools/car_support`) with plan-only wrappers `tools/{disable,restore,build-and-install}.sh`; run `python3 -m unittest discover -s tools/tests -v` for tests. Never run adb/aapt2/emulator/device commands without explicit per-operation user approval, and never auto-confirm an apply; see `PROMPT.md` and `docs/adb-workflow.md`. `BUILD_ONLY=1 tools/build-and-install.sh` builds and size-checks (<524288 bytes) with no ADB.
- **R8 & Release Optimization:** This project uses the AGP 9+ `optimization` DSL block in `app/build.gradle.kts` (`optimization { enable = true; packageScope = ... }`) rather than legacy `isMinifyEnabled`/`proguardFiles`. Place custom R8 keep rules in `app/src/main/keepRules/rules.keep` (AGP automatically merges all files under `src/main/keepRules/`).
