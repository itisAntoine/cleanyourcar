# CleanYourCar Rename Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rename the merged Android project identity to CleanYourCar throughout app code, companion module, build/install tooling, and documentation.

**Architecture:** Keep the existing two-APK architecture. Rename the launcher package to `com.cleanyourcar.launcher` and the navigation companion package to `com.cleanyourcar.navbridge`, updating their explicit-intent contract and all project-facing labels. Preserve preference keys and unrelated stock head-unit package names.

**Tech Stack:** Android Gradle Plugin, Gradle Kotlin DSL, Java 11, Android SDK APIs, Bash.

**Spec:** `docs/superpowers/specs/2026-10-29-cleanyourcar-rename-design.md`

## Global Constraints

- Keep application code in Java and source/target compatibility at Java 11.
- Keep launcher package `com.cleanyourcar.launcher` and companion package `com.cleanyourcar.navbridge`.
- Keep existing preference-key values; do not make behavior or dependency changes.
- Do not commit or push; do not alter unrelated `.idea` files or Git state.

## Review Focus

- Main and companion packages must match their new source paths and Gradle namespaces/application IDs; compile both modules.
- Companion explicit-intent targets and the launcher navigation action must agree after rename; assert new identifiers in source and build both APKs.
- Installer grants, service/activity launches, and foreground detection must target the renamed IDs; validate the script contains no stale app identifiers.
- Display labels and theme references must resolve; verify both APK builds and resource linking.
- Stock head-unit package identifiers and preference keys must remain unchanged; assert those values in a focused diff review.

---

### Task 1: Rename Android packages and app identifiers

**Files:**
- Move: `app/src/main/java/com/minimal/carplaylauncher/` to `app/src/main/java/com/cleanyourcar/launcher/`
- Move: `app/src/test/java/com/minimal/carplaylauncher/` to `app/src/test/java/com/cleanyourcar/launcher/`
- Move: `app/src/androidTest/java/com/minimal/carplaylauncher/` to `app/src/androidTest/java/com/cleanyourcar/launcher/`
- Move: `addons/nav-bridge/src/main/java/com/minimal/carplaylauncher/navbridge/` to `addons/nav-bridge/src/main/java/com/cleanyourcar/navbridge/`
- Move: `addons/nav-bridge/src/test/java/com/minimal/carplaylauncher/navbridge/` to `addons/nav-bridge/src/test/java/com/cleanyourcar/navbridge/`
- Modify: `app/build.gradle.kts`, `addons/nav-bridge/build.gradle.kts`
- Modify: app and nav-bridge manifests, `app/src/main/keepRules/rules.keep`, and Java package/import/action/target identifiers
- Modify: `ExampleInstrumentedTest.java` to assert `com.cleanyourcar.launcher`

**Interfaces:**
- Launcher package and application ID: `com.cleanyourcar.launcher`
- Companion package and application ID: `com.cleanyourcar.navbridge`
- Navigation action: `com.cleanyourcar.launcher.action.NAVIGATE`
- Companion target activity: `com.cleanyourcar.launcher.NavDispatchActivity`

- [x] **Step 1: Update package declarations and references** in main, test, and instrumentation Java sources, both manifests, Gradle configuration, keep rules, and the companion's explicit-intent contract. Preserve preference-key values and stock package identifiers.
- [x] **Step 2: Move source directories** to match the new package declarations.
- [x] **Step 3: Run unit tests** with `./gradlew :app:testDebugUnitTest :navbridge:testDebugUnitTest`; expect both tasks to pass.
- [x] **Step 4: Build both APKs** with `./gradlew :app:assembleDebug :navbridge:assembleDebug`; expect successful resource linking and compilation.

### Task 2: Rename user-facing and project references

**Files:**
- Modify: `settings.gradle.kts`, `app/src/main/res/values/strings.xml`
- Modify: `app/src/main/res/values/themes.xml`, `app/src/main/res/values-night/themes.xml`, and the app manifest theme reference
- Modify: `tools/build-and-install.sh`, `tools/disable.sh`, `examples/yt3518c-mt3561s/README.md`
- Modify: `.github/copilot-instructions.md`, `docs/ios-shortcut-setup.md`, `docs/devices/yt3518c-mt3561s.md`
- Modify/rename: existing CarPlayLauncher spec and plan under `docs/superpowers/`
- Modify: related source comments and the app's display label

- [x] **Step 1: Replace project-facing CarPlayLauncher labels** with CleanYourCar, including `rootProject.name`, the app label, and theme name; update all references to the new package IDs.
- [x] **Step 2: Update scripts and docs** while retaining CarPlay mentions that describe CarPlay compatibility and old identifiers that refer to stock head-unit software rather than this app.
- [x] **Step 3: Rename the existing spec and plan filenames** to CleanYourCar-oriented filenames and update their internal identity references.
- [x] **Step 4: Search for stale identifiers** using `grep -RInE 'com\.minimal\.carplaylauncher|CarPlayLauncher|carplay_launcher|\.carplaylauncher|Theme\.CarPlayLauncher' --exclude-dir=.git --exclude-dir=.gradle --exclude-dir=build --exclude-dir=.idea --exclude-dir=.superpowers .`; expect only the intentionally preserved `carplay_launcher_prefs` key and old-to-new references in the rename design/plan.

### Task 3: Final verification

**Files:**
- Verify all files changed in Tasks 1–2.

- [x] **Step 1: Run the complete local unit-test set** with `./gradlew :app:testDebugUnitTest :navbridge:testDebugUnitTest`; expect all tests to pass.
- [x] **Step 2: Assemble both debug APKs** with `./gradlew :app:assembleDebug :navbridge:assembleDebug`; expect both builds to succeed.
- [x] **Step 3: Review the final diff and status** to confirm no unrelated `.idea` changes, no stale app package identifiers, no preference-key changes, and no staged/committed/pushed changes.
