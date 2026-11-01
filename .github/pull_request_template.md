Tested on: <!-- Sanitized model, actual SDK and firmware; or host-only, physical qualification pending. -->

## Change

<!-- One focused topic. Explain behavior and remaining qualification gaps in English. -->

## Validation

<!-- Exact commands/results. Instrumentation compiled is not instrumentation run. -->

### Profile PRs (otherwise N/A)

- [ ] Schema-valid and passes the app codec's cross-field rules.
- [ ] Evidence class and limitations match observations, not inferred support.
- [ ] No identifiers, secrets, raw logs, location or unsanitized screenshots.
- [ ] Data only; no safety-policy/workflow edits or production qualification claims.

### App / adapter PRs (otherwise N/A)

- [ ] Relevant unit tests and layout fixtures pass; report instrumentation compile/run separately.
- [ ] No new permissions; `:app` remains without `INTERNET`.
- [ ] HUD remains fail-closed and platform restrictions are not bypassed.
- [ ] Core APK size gate passes; no prebuilt APKs or binaries committed.

### Tools PRs (otherwise N/A)

- [ ] Offline fake-ADB and recovery tests pass.
- [ ] No default device/user or auto-confirmation introduced.
- [ ] Private snapshots and explicit plan/apply/refusal semantics remain intact.

<!-- Follow AGENTS.md; physical qualification requires separate authorization. -->
