# Development status / Статус разработки

Published: 1.39, versionCode 68. PR #3 started from a811c2c and incorporates selected resolutions from PR #2. The current development changes also integrate the local stability audit and statistics work.

## Scope
- Resource lifecycle: cancel pending retry callbacks, detach recycled views, release idle players on background, cancel stale cache writers and purge cancelled queued work. Original preview concurrency, prediction, quality selection and network timeout policy retained.
- Telemetry: explicit consent, preserve old opt-out, clear old device queues and installation ID on policy migration, durable batch and acknowledgement. Worker supports v1 and v2. The admin API reads existing history in 7/14/30/90-day periods and provides all-time event counts and paged install lists without rewriting historical rows.
- Local diagnostics: separate opt-in, fixed 30-day aggregate window, 10,000-event cap, no preference data or event-level identifiers. Counts currently instrumented: first frame, playback error and player background. Other allowed fields are zero until instrumented. Export requires explicit user action. Reset or disable clears the counters; no secure-erasure guarantee.
- CI: Android unit tests/lint/debug assembly, worker tests, offline technical archive tests and restored neutral synthetic evaluation tests.
- Documentation: published vs development status, accurate statistics disclosures, bilingual README and Pages source.

## Release verification

- PR #3 passed GitHub CI. The 1.39 release build and Gradle lint passed locally, with 258 unit tests passing in each Android variant and nine Worker tests.
- The signed APK updated 1.38 on an Android emulator. A previous default-on statistics setting required fresh consent. Feed navigation and player playback were checked through an 800 ms / 5 Mbit/s connection.
- The v2 Worker was deployed to the existing Durable Object. The release check confirmed preservation of historical records for 238 installations and verified that two deliveries of a synthetic batch incremented its counter once.
- No device performance benchmarks are reported. Local diagnostics remain optional and separate from server statistics.

## По-русски

Версия 1.39 включает исправления ленты, поиска и плеера из PR #3 и последующей интеграции. Статистика требует явного согласия. Подписанное обновление проверено на эмуляторе, а история статистики сохранилась после перехода Worker на v2.

## Merge resolution (PR #2 into PR #3)
The merge has both branch tips as parents. Keep PR #3's 1.38 application, version, current documentation, lifecycle fixes, consent migration and local aggregate diagnostics. Import the old branch's network-log path redaction, durable preference migration, and independent neutral offline test suite. The earlier feature-level journal, replay/model checkpoint changes, ranking/dedup changes, source review/parser experiments and obsolete release documentation are intentionally not activated or restored in the merged tree. They remain accessible in the second parent. No model or content selection changes, no source integrations. The pre-merge PR #3 CI run 46010146815 passed; merged head requires a new check.
