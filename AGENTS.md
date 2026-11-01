# CleanYourCar agent guide

For GitHub Copilot, Claude, Gemini and other coding assistants. Start with the job,
not a device command. User conversations are Vietnamese; code, comments, documentation,
commits and workspace artifacts are English. Follow the language policy in
[Copilot instructions](.github/copilot-instructions.md).

## Which job are you doing?

| Job | Start here |
|---|---|
| Set up one head unit | Follow [PROMPT.md](PROMPT.md). Edit only a local profile/config; do not open a PR unless asked. Repository guidance is not device authorization. |
| Add a device profile | Follow [the contribution guide](docs/contributing-device-profile.md). Keep the contribution data-only and report evidence honestly. |
| Change core, adapters or tools | Read the safety rules and repo map below, then select the owning layer. |

## Head-unit safety rules

- Setup, text entry, configuration and checks are parked-only; no universal driving detector is claimed.
- Disable, never uninstall, debloat candidates. Never disable protected/core packages; no override exists.
- Use [the plan workflow](docs/adb-workflow.md), with explicit --serial and --user for every device command. Ask before each operation, even a read-only preview.
- A future qualified write needs a durable snapshot before the first write, a reviewed saved plan, and the user's own confirmation hash. Never calculate/pass approval on their behalf.
- Restore through a recorded operation's restore plan and its separate confirmed apply; keep the same private record root. This is not firmware or app-data recovery.
- Check HOME ownership before any proposed stock-launcher disable. Current tools refuse disabling HOME providers and leave HOME selection user-directed; do not bypass that guard.
- HUD is off by default and **denied on all current adapters**. Unknown reverse state fails closed; profile evidence cannot enable it.
- Production install/debloat qualification registries are empty: **no firmware is write-supported**. Offline fake-ADB completion is not physical qualification.
- On refusal, stop and report the reason. Do not retry in a loop, bypass OEM restrictions, use ad hoc fallback commands, or lower minSdk.
- If network ADB was opened with separate authorization, turn it off afterward through the unit's supported controls when available.

Command form only, **not permission to run**; the user supplies each placeholder and confirmation:

```sh
python3 tools/car.py apply --serial <transport> --user <id> --plan <private/plan.json> --confirm <hash>
```

## Repo map

| Path | Owner / contract |
|---|---|
| `app/` | Core launcher, Gradle :app, framework UI, no INTERNET permission. |
| `app/src/main/java/com/cleanyourcar/launcher/` | Java UI, compat policy, configuration, layout and built-in integrations. |
| `addons/nav-bridge/` | Optional Gradle :navbridge companion; separately consented BLE/LAN reception and paired-token authentication. |
| `profiles/schema.json`, `profiles/devices/`, `profiles/fixtures/` | Data schema, bundled profile candidates, valid/invalid test inputs. |
| `examples/`, `docs/devices/` | Reported hardware notes and unqualified candidate lists, not production write approvals. |
| `tools/car.py`, `tools/car_support/` | Python 3.9+ stdlib CLI, explicit targets, private snapshots and fail-closed writes. |
| `tools/disable.sh`, `tools/restore.sh`, `tools/build-and-install.sh` | Plan-only wrappers; no automatic apply or confirmation. |
| `tools/tests/` | Offline fake-ADB tests and guidance/link guards. |
| `docs/compatibility.md`, `docs/adb-records.md` | Feature-specific qualification gaps and recovery contract. |
| `SECURITY.md`, `docs/TROUBLESHOOTING.md`, `docs/DOS-AND-DONTS.md` | Private reporting, incident handling and contribution boundaries. |
| `settings.gradle.kts`, `gradle/libs.versions.toml` | Actual module paths and dependency catalog. |
| `.github/pull_request_template.md` | Conditional review checklist. |

## Where a change goes

| Change | Route |
|---|---|
| Match facts, role defaults, protected-package data | Profile PR via the contribution guide; schema-valid, sanitized, evidence-classified. |
| New vendor protocol or verified reverse mechanism | Built-in Java adapter and tests, not executable profile fields. |
| Shared UI, routing, config or compatibility behavior | Core code and relevant unit/layout fixtures; keep generic/AAOS restrictions explicit. |
| Host planning, journaling or parser behavior | Tools plus offline fake-device regression tests; preserve refusal and recovery semantics. |
| Safety policy, protected-package guard, APK gate or CI/workflows | Open an issue for the maintainer before proposing code; never bundle with a profile PR. |

## Before opening a PR

Run from the repository root. For docs/tools, use the Python suite; it needs no dependencies.
For app/adapter changes, also use the relevant Gradle commands below. Java source/target is
11; minSdk = 23. The wrapper uses a Java 25 daemon; inspect build files before changing SDKs.

```sh
python3 -m unittest discover -s tools/tests -v
git ls-files --eol -- '*.sh' gradlew
./gradlew :app:testDebugUnitTest :navbridge:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug :navbridge:assembleDebug
./gradlew :app:assembleDebugAndroidTest
BUILD_ONLY=1 tools/build-and-install.sh
```

The core APK must be below 524288 bytes; >= 524288 bytes is rejected by the build wrapper.
The optional bridge is separate from that core budget. Both release build types use debug
signing and are development artifacts, not distributable production releases.
Instrumentation is **compile-only** here; physical/emulator runs need separate per-operation
authorization and remain pending. Host tests do not establish HOME, media, HUD or firmware
write support. Review the diff for identifiers and check that no agent skills are tracked.

## Hard rules

- No prebuilt APKs or binaries; keep source reviewable.
- No INTERNET permission in :app; networking belongs only in the optional companion.
- Profiles are data only; no commands, grants or safety overrides.
- No secrets, tokens, serials, IPs, VINs, raw logs or location in commits or screenshots.
- No safety-policy or workflow edits in a profile PR.
- No vendored agent skills or references to local skill installations.
- Java-only app/test code, Java 11 compatible; Kotlin DSL is for builds only.
- Dependencies go through `gradle/libs.versions.toml`; no module-level repositories.

## Commit and PR style

One focused topic per PR. Use an English imperative commit subject and describe behavior,
tests and remaining qualification gaps. Begin the PR with “Tested on:” including sanitized
head-unit/firmware facts or “host-only; physical qualification pending”. Fill only relevant
checklists; screenshots must be sanitized, not proof of untested capabilities.
