# Development status / Статус разработки

27 November 2026. Published: 1.38, versionCode 67. Base: main a811c2c. New development branch: prepare/stability-1.38. PR #2 is merged into the PR #3 development branch with explicit conflict resolutions. Main remains unchanged.

## Scope
- Resource lifecycle: cancel pending retry callbacks, detach recycled views, release idle players on background, cancel stale cache writers and purge cancelled queued work. Original preview concurrency, prediction, quality selection and network timeout policy retained.
- Telemetry: explicit consent, preserve old opt-out, clear old queues and installation ID on policy migration, durable batch and acknowledgement. Worker supports v1 and v2.
- Local diagnostics: separate opt-in, fixed 30-day aggregate window, 10,000-event cap, no preference data or event-level identifiers. Counts currently instrumented: first frame, playback error and player background. Other allowed fields are zero until instrumented. Export requires explicit user action. Reset or disable clears the counters; no secure-erasure guarantee.
- CI: Android unit tests/lint/debug assembly, worker tests, offline technical archive tests and restored neutral synthetic evaluation tests.
- Documentation: published vs development status, accurate statistics disclosures, bilingual README and Pages source.

## Release gates
1. Review current head CI; no claim of passing until completed.
2. Run Android device tests for rotation, repeated attach/detach, background/foreground, process death, low memory and interrupted network with synthetic fixtures.
3. Measure main-thread latency, frame timing, peak memory and battery before further tuning. Do not change concurrency or timeout policy without measurements.
4. Test telemetry migration: fresh install, previous opt-out, previous default-on, disconnect during upload, retry acknowledgement, reset ID. Deploy and validate compatible v2 Worker before enabling a new client release; deployment not performed.
5. Test local diagnostics enable, disable, clear, expiry, export, and verify absence of preference fields. Private app storage is not encryption; existing model format and history semantics are unchanged; preference migration now commits the destination before deleting legacy keys.
6. Verify package upgrade and signing with owner key, then obtain approval to publish. No release bump, main merge, worker deployment or signing performed.

No production training, model changes, content data import or source adapters in this branch. No measured device performance claimed.

## По-русски
Опубликована 1.38. Исправления ресурсов, приватности, технических счётчиков и CI находятся в новой ветке, основанной на актуальной main. Перед выпуском необходимы проверки на устройстве, тестирование Worker v2, подпись и согласие владельца. История старого PR сохранена в merge-коммите; основная ветка не изменена.

## Merge resolution (PR #2 into PR #3)
The merge has both branch tips as parents. Keep PR #3's 1.38 application, version, current documentation, lifecycle fixes, consent migration and local aggregate diagnostics. Import the old branch's network-log path redaction, durable preference migration, and independent neutral offline test suite. The earlier feature-level journal, replay/model checkpoint changes, ranking/dedup changes, source review/parser experiments and obsolete release documentation are intentionally not activated or restored in the merged tree. They remain accessible in the second parent. No model or content selection changes, no source integrations. The pre-merge PR #3 CI run 46010146815 passed; merged head requires a new check.
