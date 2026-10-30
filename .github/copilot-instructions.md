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
- **Run all local unit tests:** `./gradlew :app:testDebugUnitTest`
- **Run a single unit test class:** `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.ExampleUnitTest"`
- **Run a single unit test method:** `./gradlew :app:testDebugUnitTest --tests "com.cleanyourcar.launcher.ExampleUnitTest.addition_isCorrect"`
- **Run instrumented tests (requires connected device/emulator):** `./gradlew :app:connectedDebugAndroidTest`
- **Run a single instrumented test class:** `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.cleanyourcar.launcher.ExampleInstrumentedTest`

> **Note:** In `app/build.gradle.kts`, library and test dependencies (`appcompat`, `material`, `junit`, `espresso-core`, `ext-junit`) are declared in `gradle/libs.versions.toml` but currently commented out in the `dependencies` block. Uncomment the relevant catalog entries before compiling code or running tests that depend on them (`Theme.CleanYourCar` in `res/values/themes.xml` inherits from `Theme.MaterialComponents.DayNight.DarkActionBar`, which requires `libs.material` / `libs.appcompat` during resource linking).

## Architecture & Project Structure

- **Single-Module Android Application:** Only `:app` is included in `settings.gradle.kts`, with namespace and `applicationId` set to `com.cleanyourcar.launcher`.
- **Toolchain & SDK Configuration:**
  - Gradle `9.6.0` with Android Gradle Plugin (AGP) `9.4.1` and Kotlin DSL (`.gradle.kts`).
  - Gradle Daemon JVM toolchain is pinned to Java 25 (`gradle/gradle-daemon-jvm.properties` via Foojay resolver), while app source/target compatibility is Java 11 (`compileSdk = 37`, `minSdk = 26`, `targetSdk = 37`).
  - Gradle configuration cache is enabled by default (`org.gradle.configuration-cache=true` in `gradle.properties`); any custom Gradle tasks or build logic must be configuration-cache compatible.
- **Current Scaffold State:** The project is at initial scaffold state with no `Activity` or main source code under `app/src/main/java` yet.

## Key Conventions

- **Java-Only Application Code:** Write all application, test, and instrumentation code strictly in **Java** (Java 11 compatibility). Do not introduce Kotlin source files (`.kt`) for app or test logic; Kotlin DSL (`.gradle.kts`) is used only for Gradle build scripts.
- **Dependency Management:** Always declare dependencies and versions in `gradle/libs.versions.toml` and reference them via the `libs` version catalog in `app/build.gradle.kts`. Repositories are strictly managed in `settings.gradle.kts` (`RepositoriesMode.FAIL_ON_PROJECT_REPOS`); do not add `repositories {}` blocks to module build files.
- **R8 & Release Optimization:** This project uses the AGP 9+ `optimization` DSL block in `app/build.gradle.kts` (`optimization { enable = true; packageScope = ... }`) rather than legacy `isMinifyEnabled`/`proguardFiles`. Place custom R8 keep rules in `app/src/main/keepRules/rules.keep` (AGP automatically merges all files under `src/main/keepRules/`).
