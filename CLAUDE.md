# Loopa: handoff to Claude agent

## Mission
Finish the work on branch `prepare/claude-release-training`, verify it on Android, review privacy, and prepare an owner-approved release. Do not merge or publish blindly. The base was main at `6e8c55246b491e00594deff5d1839a46ff6ecaa8` (version 1.34 / code 63). Changes were made through the GitHub API; no Android SDK or network clone was available to the author. No tests have yet been run against the changed repository. Treat this branch as an unverified draft.

## Start here
1. Fetch latest main; inspect the diff, check for conflicts and any upstream changes. Do not rewrite main or use force push.
2. Inspect `docs/TRAINING_RELEASE_HANDOFF.md` for scope, limitations and acceptance tests.
3. Run the full Android unit suite and lint/build, plus Node worker tests. Fix all compiler and test failures before doing anything else.
4. Review the privacy boundaries: local preference data must not leave the device; telemetry is opt-in and technical only. Do not add content IDs, URLs, titles, searches, tags, account identifiers or preference vectors to telemetry/log export. No synthetic user data in production.
5. Deploy and validate the backwards-compatible worker before releasing the APK. Do not deploy without the owner's credentials and approval. Do not read/print signing keys or admin tokens.
6. Build a correctly signed release using the existing owner key, only after checks pass. Never publish an APK signed with the debug key. Confirm version bump and update mechanism with the owner.

## Commands (JDK 17, Android SDK 35, Node 22.13+)
```bash
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
(cd stats-worker && npm test)
```
Windows: use `gradlew.bat`. On a device/emulator, exercise upgrade from existing 1.34, cold start, process death, offline/online, disable/reset/re-enable, API 26 and API 35. All new local UI strings need the project's i18n process before publishing.

## Rules
- Preserve current user data during upgrade, except old telemetry queue/ID intentionally discarded before fresh consent.
- No changes to signing secrets, worker credentials, live deployment or release tags without explicit owner approval.
- Keep existing age controls and source restrictions; test them unchanged.
- Use local synthetic fixtures for tests, not real user profiles.
- Report what actually passed, failed, or remains pending. Keep rollback instructions and a reproducible release record.

## Additional owner-selected provider work
See `docs/CREATOR_SOURCE_REVIEW.md` for the five selected providers and their individual access/authorization blockers. These are not implemented adapters; keep them disabled until the documented release gate is satisfied.
