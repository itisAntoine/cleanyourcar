# Head Unit Profiles

Sanitized head-unit facts now live in two places:

- **Machine-readable profiles:** `profiles/devices/<profile-id>/profile.json`. They are validated against [`profiles/schema.json`](../../profiles/schema.json) and bundled with the app as candidate matches.
- **Human-readable notes:** `docs/devices/<profile-id>.md`, for example [yt3518c-mt3561s.md](yt3518c-mt3561s.md).

Profiles never include serial numbers, MAC or IP addresses, account identifiers, coordinates or personal media.

To add a unit, see [contributing-device-profile.md](../contributing-device-profile.md). For support badges and evidence states, see [compatibility.md](../compatibility.md).
