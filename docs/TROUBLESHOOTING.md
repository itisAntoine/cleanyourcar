# Troubleshooting

Follow [AGENTS.md](../AGENTS.md) and [PROMPT.md](../PROMPT.md). Park before setup or
diagnosis. Stop at a refusal; no command below grants device authorization.
Host-side tooling is tested offline only. Physical qualification remains pending,
production write registries are empty, and HUD is denied on all current adapters.

## Incident format

Record **symptom → device/firmware → cause with command evidence → fix → link**.
Use sanitized model, actual SDK and firmware facts. Cite only the relevant reason code
or command exit/readback from a private approved operation, never raw logs or identifiers.
The cases below are known failure modes/contracts, not newly qualified hardware incidents.

## No HOME after a stock launcher was disabled

- **Device/firmware:** record the sanitized unit and actual SDK.
- **Cause/evidence:** compare the private before-state HOME providers and the failed
  HOME resolution result. Current tools refuse disabling HOME providers; this symptom
  can follow an external/legacy change, not a supported workaround.
- **Fix:** stop debloat. Use the unit's supported Settings interface to select a working
  HOME app, if accessible. If a tool-owned operation exists, review its recorded restore
  plan under the same target/user/record root; never blindly enable a package list.
  Without a valid record or accessible Settings, request manufacturer/maintainer recovery
  guidance rather than guessing shell commands.
- **Link:** [recorded restore boundaries](adb-records.md).

## Unit reboots or loses a function after debloat

- **Device/firmware:** include the affected firmware and lost function (camera, audio,
  Bluetooth or other), without personal data.
- **Cause/evidence:** identify the recorded operation and exact before/after package
  states privately. Do not infer that a similarly named package is safe.
- **Fix:** stop further changes. If reachable and a valid tool-owned record exists,
  request approval for a target-bound restore preview from that operation, review it,
  then request separate apply approval. Restore can refuse changed firmware/identity or
  unsupported inverses; preserve that refusal and escalate. It is not firmware repair.
- **Link:** [recovery contract](adb-records.md), [workflow](adb-workflow.md).

## HUD appears while reversing

- **Device/firmware:** record adapter and sanitized firmware; no current adapter is
  HUD-ready.
- **Cause/evidence:** include the compatibility reason and whether the overlay belonged
  to this app or another process. A screenshot does not prove a reverse signal.
- **Fix:** stop using HUD and disable it in the app's supported settings. Keep it off
  and report sanitized reproduction; do not attempt a camera/HUD timing test while driving
  or bypass unknown reverse state. Use OEM recovery if camera availability is affected.
- **Link:** [HUD and reverse camera](compatibility.md#hud-and-reverse-camera).

## API 23 unit lacks cmd package

- **Device/firmware:** report actual SDK 23 rather than the marketed Android release.
- **Cause/evidence:** cite the private approved discovery command's unsupported-command
  reason and unresolved HOME/package facts. The discovery parser covers older command
  availability; unresolved facts stay unresolved.
- **Fix:** use only the existing discovery workflow when explicitly authorized. Let it
  report incomplete facts. Stop if planning refuses; do not manually substitute a legacy
  write, assume HOME ownership, or lower minSdk. Share sanitized reason codes with a
  tools issue, not raw dumps.
- **Link:** [ADB discovery and release gate](adb-workflow.md).

## Navigation bridge is unreachable

- **Device/firmware:** record SDK, selected BLE/LAN mode and bridge reason code.
- **Cause/evidence:** distinguish “listener not started” from authentication rejection
  using the bridge UI status and sanitized error, not a guessed network address.
  LAN is refused on API 34+ (`LAN_LIFECYCLE_UNAVAILABLE`).
- **Fix:** leave reception off unless the user opts in. On eligible systems, check
  the separately enabled channel, supported permissions and locally paired token through
  normal UI; never publish the token. On API 34+, do not bypass the LAN lifecycle gate;
  BLE may be available if its own requirements pass. Do not open public ports, disable
  authentication or grant permissions with shell commands.
- **Link:** [bridge contract](compatibility.md#navigation-bridge-navbridge),
  [private security reporting](../SECURITY.md).
