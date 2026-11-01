# Contribution boundaries

Read [AGENTS.md](../AGENTS.md) for job routing and checks.

| Do / do not | Reason |
|---|---|
| Commit source, not prebuilt APKs or binaries. | Review must cover the behavior being shipped. |
| Keep `INTERNET` out of `:app`; networking stays in the optional bridge. | The core launcher remains offline. |
| Treat profiles as validated data, never commands or grants. | Imported facts cannot execute or override safety policy. |
| Remove secrets, tokens, serials, IPs, VINs, raw logs and location from commits/screenshots. | Reports must not expose users, networks or vehicles. |
| Keep safety-policy and workflow edits out of profile PRs; ask the maintainer first. | Data contributions cannot redefine trust boundaries. |
| Do not vendor agent skills. | Machine-specific tools must not become repository dependencies. |
| Write app and tests in Java 11-compatible Java; reserve Kotlin DSL for Gradle. | Preserve the source/toolchain contract. |
| Add dependencies through the version catalog; no module repositories. | Keep resolution centrally reviewable. |
| Plan, snapshot and confirm each qualified write; disable, never uninstall debloat candidates. | Recovery must preserve exact recorded state rather than guess defaults. |
| Stop on a refusal; never bypass HOME, protection or OEM restrictions. | A successful shell exit is not evidence of automotive safety. |
| Say “host-side complete; physical qualification pending” where appropriate. | Fake-device tests, profiles and screenshots do not qualify firmware. |

No production firmware is write-supported and HUD is denied on all current adapters.
Use [the ADB workflow](adb-workflow.md) and [private records](adb-records.md) rather than
ad hoc recovery commands. Vulnerabilities belong in the [private reporting process](../SECURITY.md),
not public issues.
