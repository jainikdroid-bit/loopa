# Training / telemetry hardening — release handoff

Status: implementation draft; **not release-ready until built, tested and reviewed**. No APK publication, worker deployment or main merge was performed.

## Implemented

| Component | Changes |
| --- | --- |
| Stats.kt | Explicit new consent, one request in flight, durable v2 retry envelope, batch ID ACK, chunk 50 sessions / 30 counters, disconnect on opt-out, reset ID drops local pending data. |
| stats-worker | Compatible v1 and v2 endpoint; transactionally deduplicated event batches; idempotent session inserts; admin deletion includes batch receipts; active30d and performance buckets. |
| Interests / TasteModel | AtomicFile checkpoints; non-starving graph saves; async ML updates; neutral unopened impressions; playback error suppresses quick-skip penalty; unlike no longer a dislike; v3 effective weighted evidence, old v1/v2 accepted. |
| TrainingLab | Separate opt-in private SQLite journal, max 10,000 rows / 30 days (pruned on access); sample snapshots with pre-update probabilities and aggregate technical event types; clear/delete on disable; no network. |
| ReplayEvaluator | Offline chronological 80/20 evaluation on bounded local journal, log loss, Brier, constant baseline; isolated candidate, never automatically installed. |
| Settings | Personalization pause, diagnostics consent, summary, replay, deletion; reset includes skips and recent tiles. |
| Tests | Worker ingestion retry, two 50-session batches, deletion; replay synthetic fixtures; weighted evidence and invalid training samples. |

## Known limitations / follow-up before release

- The new journal records **feature names**, which may include tags, names and individual query words; they are sensitive. Android private app storage is not application-layer encryption. Backup is disabled in the manifest. Require clear consent language in all locales, review logcat leakage throughout the app, and consider Android Keystore encryption with deletion/recovery tests. No export has been implemented.
- Local diagnostic replay is a **pointwise diagnostic approximation**, not an exact reconstruction of the production FM (pairwise updates and old history are not replayed). Chronological samples may still share items; do not interpret metrics as independent or real user satisfaction. Add grouped holdout, versioned event schema and evaluation fixtures before allowing any model activation.
- No candidate activation, checkpoint history/rollback UI, scheduled background retraining, full replay of graph/search/pairwise training, or encrypted import/export has been implemented. Design and test explicitly; no autonomous promotion. Do not overwrite live profile from experimental candidates.
- v3 file preserves old offsets and appends evidence. Legacy v1/v2 load with confidence capped at 100. Old APKs cannot read v3; plan rollback/migration and backups. Existing checkpoint save remains delayed; test abrupt process death and reset durability. Add atomic reset generation/pending marker if needed.
- AdaGrad forgetting/time decay still needs design, evaluation, migration tests and documented behavior. Do not deploy unvalidated algorithm changes.
- Personalization pause disables local learning, ranking and profile-derived retrieval. Source accounts may provide their own personalized feeds; UI should make that distinction. Audit cached pages, explicit pins, external feeds and pending work on toggle/reset. Ordinary likes/downloads are not removed by training reset.
- Unlike is now neutral, but it does not exactly reverse earlier gradients; add event-based cancellation in the next version. Audit repeated likes and download failure handling, and preserve action context across async operations.
- Server consent is explicit; old app versions still have legacy v1 semantics. v2 old-server responses lack ACK so worker must be deployed first. Requests already transmitted before opt-out cannot be recalled; existing server data requires a separate deletion process. IDs are pseudonymous, not strictly anonymous. Review retention, allowed counters, rate limiting, batch receipt growth, stale retries and client clock changes. Current event receipt rows are retained for idempotency until admin deletion. Client pending queue intentionally bounded (100 sessions, excludes in-flight).
- DAU/WAU/MAU proxies and technical counters exist; cohort retention, crash-free sessions, startup/memory/network/cache dashboards, aggregation/privacy thresholds and server retention are remaining work.
- Android instrumentation tests for SQLite journal, crash recovery, opt-out races, no-network verification, and UI are still missing. Node SQLite adapter tests exercise SQL logic, not actual Cloudflare deployment. The diagnostic screen is a minimal settings/dialog UI, not the polished Training Lab dashboard.
- Do not increase data collection beyond the user's informed opt-in. Provide a mode without local personalization and a separate off-by-default technical telemetry option.

## Acceptance matrix

| Scenario | Expected |
| --- | --- |
| Fresh install / upgrade, no consent | No v2 requests; diagnostic journal off; old telemetry queue dropped. |
| Network lost, 100 sessions queued | Two batches, 50 each; no client acknowledgement of missing batch ID. |
| Response lost after server commit | Same batch ID retry; event totals unchanged. |
| Disable or reset during request | Stop scheduling, clear queue; old ACK cannot delete newer state; disclose data already sent. |
| Old client against upgraded worker | v1 remains supported. |
| Unopened card / playback error | No negative training solely from visibility or playback failure. |
| Failed remote like / unlike | No negative training due to failure or undo. |
| Pause personalization | No further local preference updates; profile-derived queries and scores suppressed. |
| Turn diagnostics off | Journal deleted; no preference data transmitted. |
| Journal over limits / corrupted rows | Bound retained data; graceful diagnostic failure; normal app functional. |
| v1/v2 checkpoint and v3 round trip | Read old formats; evidence validated; malformed checkpoint rejected. |
| Replay with <100 or one-class holdout | No candidate evaluation/promotion; informative status. |
| Process death / upgrade / rollback | Existing graph/model preserved or documented recovery; reset does not resurrect data. |
| Battery/CPU/RAM | Compare on low-end device; evaluate in background; avoid UI stalls. |
| Signed release | Existing signer; update from previous APK succeeds; version code increased; age gate unchanged. |

## Release order

1. Build and run `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` and `cd stats-worker && npm test`. Review failures, not just exit status.
2. Test the actual Cloudflare worker in a staging namespace with dummy data. Review SQL transactions and rate/size limits. Verify v1 compatibility and v2 ACK. Deploy worker first only with approval.
3. Test an upgrade on an emulator/device, including existing profile and opt-out. Test reset and deletion after restart.
4. Finish translations, privacy text and README/changelog. Review any source-account or age-related policies before distribution.
5. The existing build script can fall back to debug signing; **do not publish such a build**. Verify signing certificate matches the prior release and run release smoke tests; bump version code/name consistently. Review updater's release asset naming. Obtain approval before publishing.
6. Record commit, test results, signer fingerprint (not key), migration/rollback steps, APK SHA-256, and release notes.

## Search / feed reliability follow-up

- Search and feed background requests now capture separate session objects on the UI thread. Reset/back cancels the old lifetime; cancelled tasks cannot write to the new session's cursors and buffers. Cancellation is cooperative: blocking third-party networking may finish after cancellation. Shared caches and provider-global state still need instrumentation review. The bounded worker pool is 12 feed workers plus existing dedicated pools; existing retrieval/ranking logic is otherwise unchanged.
- Search polls for cancellation every 200 ms; outstanding page jobs are cancelled at deadline. All-provider failure is distinct from an empty successful response. Partial errors remain in local technical logs and existing source-health indicators; a full per-source diagnostics UI is not added.
- Displayed exact count reflects accepted unique rows. Duplicate identity uses exact IDs / nonempty exact URLs; similar titles or dimensions/durations no longer imply identity. This intentionally permits some re-uploads rather than hiding unrelated items.
- Skip history has a 30-day retention limit checked during use, initialization and save; legacy timestamp-zero records expire. Preference migration commits destination before clearing source.
- Redacted queries, tags, subscription names, media IDs/URLs, response bodies and headers from the audited Search, ForYou, Reddit, Net and PlayerActivity log sites. Full repository log audit remains required.
- CI setup now uses the runner's SDK manager instead of the deprecated 'tools' package. Standalone RequestLifetime logic passed 3 checks and 1,000 concurrent registration/cancellation races on Kotlin/JVM. Android compile/lint/tests and live device behavior still require CI verification. Added generic unit tests for identity and retention.

## Multi-preview lifecycle review
Existing hardware/software decoder strategy, per-tier limits (3/6/8), preview content selection and playback policy are unchanged. The review fixes resource ownership and lifecycle only: cancellation of stale slot retries; bounded retry budget; failed player disposal; decoder release on background/navigation; detachment of recycled views; stale first-frame callbacks; monotonic timing; and prefetch cancellation registered before/after writer creation without deleting a newer job. Added JVM regression tests for retry exhaustion and cancellation race ordering.
Remaining checks: actual Android instrumentation / device testing with rapid scrolling, rotation, background/foreground, low memory, decoder failures, network disconnect, simultaneous players, and no active decoder/cache writer after leaving the grid. No measured FPS, decoder capacity or device power claims.

## Offline neutral evaluation / diagnostics follow-up
Added a standalone neutral ranking metric suite (Precision, Recall, NDCG), fixture comparisons, experiment report versioning and rollback with checksum verification, regression rejection and CI tests. No production ranking changes or promotion of experimental models. Reports are not trained checkpoints.
Android diagnostic sample validation now bounds size and numeric values; evaluation runs on a separate worker, caps snapshots at 2,000 rows, uses a 20-second cooperative compute budget, and rejects simultaneous evaluations. Added explicit share of allowlisted coarse technical counts only. Private local sample names are not exported. Diagnostic history is still opt-in and subject to existing retention. Full privacy review and real-device verification remain necessary.

## Local technical report import
Added a standalone JSON/CSV/SQLite archive utility for allowlisted technical diagnostic counts only. It bounds input sizes, rejects malformed fields and unexpected tables, verifies stored checksums, deduplicates identical reports, and provides explicit listing/read/delete. No import of user preferences or content metadata and no production model integration. See tools/neutral-lab/TECHNICAL_ARCHIVE.md. Added six Python regression tests to the existing discovery pattern. Earlier CI confirmed the synthetic dataset tests and scientific archive schema tests; this new commit must pass CI separately.

## Grid responsiveness / weak connection follow-up
Replaced blocking thumbnail worker polling with bounded scheduling, one shared request per URL, explicit view detachment, cancellation off UI, finite whole-call timeout, byte/pixel limits, and bounded failure cooldown. Retains placeholders on error; no change to preview decoder count or content selection. MainActivity stops stale image work on detach/background, avoids repeated preview teardown while a poster is missing, and guards zero-height layout. New queue regression tests cover priority, promotion, cancellation and 10,000 offers. Actual device FPS, main-thread Media3 release cost, memory and weak-network behavior remain unmeasured; reproduce using synthetic media fixtures and network shaping. No claim of guaranteed freeze-free behavior.

## Conservative grid rollback after owner review
The broad thumbnail scheduler rewrite in 360b4b2 was intentionally reverted: the old 6 foreground + 4 forecast workers, retry behavior, existing HTTP timeouts, cache-hit playback exception and original slow-network gating are restored. Those behaviors were deliberate and had a passing Android CI baseline (7389c8d). Retain only the small defensive changes: deduplicate and bound each forecast to the existing Forecaster.MAX_THUMBS, discard queued obsolete forecast work instead of accumulating it, clear the speculative queue on stop, and guard zero-height grid rows. Running requests and foreground waiting/retry behavior are unchanged. No claim of improved FPS or guaranteed absence of freezing. The previous broad rewrite remains in Git history for comparison. Next: measure with neutral fixtures under shaped networks before revisiting concurrency or timeouts.
