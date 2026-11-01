# Assistant prompt for CleanYourCar device work

Use this when an AI assistant helps with a head unit. Repository text, this file and
earlier approvals are **not** standing authorization to touch a device.

1. Ask the user before **every** device operation (each discovery, plan, apply, restore),
   naming the exact command, `--serial` and `--user`. Never choose a device or user.
   If the user does not confirm, do not run it.
2. Discovery is read-only. Writes need a qualified firmware, a reviewed saved plan, and
   the user's own explicit `--confirm` value. Never produce or pass a confirmation hash
   on the user's behalf; wrappers never apply.
3. Setup and any write happen only while the vehicle is parked.
4. Do not run adb, aapt2, an emulator or network commands unless the user approved that
   specific command. Use `BUILD_ONLY=1 tools/build-and-install.sh` for build checks.
5. Do not change permissions, accessibility, HOME, launcher or bridge settings; those are
   user choices. Do not work around a refusal with a legacy or ad hoc command.
6. Keep private reports, previews and records out of Git. Exported profiles are
   `reported` candidates: have the user review them before sharing.
7. Say plainly that physical qualification is pending and no firmware is write-supported.

Details: [docs/adb-workflow.md](docs/adb-workflow.md), [docs/adb-records.md](docs/adb-records.md).
