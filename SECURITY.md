# Security

## Report privately

Do not file vulnerabilities in public issues or attach private records to a PR.
Use GitHub private vulnerability reporting from this repository's **Security → Report
a vulnerability** control **if available**. Its enabled state has not been verified.
Maintainers must enable private vulnerability reporting in repository Settings →
Code security before this channel can be used (see
[GitHub setup instructions](https://docs.github.com/en/code-security/security-advisories/working-with-repository-security-advisories/configuring-private-vulnerability-reporting-for-a-repository)).

If the control is absent, do not publish exploit details or identifiers. Ask a maintainer
to enable the private channel with a generic request containing no sensitive evidence.
No alternative private address or response-time guarantee is currently established.

Once a private channel is available, report the affected revision, component, impact and
minimal sanitized reproduction. Do not send tokens, serials, IP addresses, VINs, raw device
logs, private ADB snapshots or precise location. Agree on any sensitive evidence handling
privately before sharing it.

## Scope

- `tools/`: target binding, confirmation, protected packages, snapshots and restore.
- Profile import/parser: validation, size limits, atomic configuration and data-only input.
- `:navbridge`: token authentication, inbound parsing and BLE/LAN lifecycle.
- `:app`: permission boundaries, exported entry points, routing and fail-closed HUD policy.

## Limits, not certifications

This project is not a vehicle-safety certification or a replacement for OEM camera,
CAN/MCU, braking or firmware recovery systems. HUD removal timing is a design target,
not a guaranteed latency; all current adapters deny HUD. LAN tokens authenticate
requests but **are not transport encryption**; do not assume confidentiality on a LAN.

Host-side tests do not qualify physical firmware. Production write registries are empty;
no installation/debloat firmware is write-supported. See
[compatibility](docs/compatibility.md), [agent guidance](AGENTS.md) and
[troubleshooting](docs/TROUBLESHOOTING.md).
