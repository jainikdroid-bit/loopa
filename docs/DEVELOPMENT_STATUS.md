# Development status / Статус разработки

27 November 2026. Published: 1.38, versionCode 67. Base: main a811c2c. New development branch: prepare/stability-1.38. Previous PR #2 is preserved as an archive, not merged.

## Scope
- Resource lifecycle: cancel pending retry callbacks, detach recycled views, release idle players on background, cancel stale cache writers and purge cancelled queued work. Original preview concurrency, prediction, quality selection and network timeout policy retained.
- Telemetry: explicit consent, preserve old opt-out, clear old queues and installation ID on policy migration, durable batch and acknowledgement. Worker supports v1 and v2.
- Local diagnostics: separate opt-in, fixed 30-day aggregate window, 10,000-event cap, no preference data or event-level identifiers. Counts currently instrumented: first frame, playback error and player background. Other allowed fields are zero until instrumented. Export requires explicit user action. Reset or disable clears the counters; no secure-erasure guarantee.
- CI: Android unit tests/lint/debug assembly, worker tests, offline technical archive tests.
- Documentation: published vs development status, accurate statistics disclosures, bilingual README and Pages source.

## Release gates
1. Review current head CI; no claim of passing until completed.
2. Run Android device tests for rotation, repeated attach/detach, background/foreground, process death, low memory and interrupted network with synthetic fixtures.
3. Measure main-thread latency, frame timing, peak memory and battery before further tuning. Do not change concurrency or timeout policy without measurements.
4. Test telemetry migration: fresh install, previous opt-out, previous default-on, disconnect during upload, retry acknowledgement, reset ID. Deploy and validate compatible v2 Worker before enabling a new client release; deployment not performed.
5. Test local diagnostics enable, disable, clear, expiry, export, and verify absence of preference fields. Private app storage is not encryption; existing model/history storage is unchanged.
6. Verify package upgrade and signing with owner key, then obtain approval to publish. No release bump, merge, worker deployment or signing performed.

No production training, model changes, content data import or source adapters in this branch. No measured device performance claimed.

## По-русски
Опубликована 1.38. Исправления ресурсов, приватности, технических счётчиков и CI находятся в новой ветке, основанной на актуальной main. Перед выпуском необходимы проверки на устройстве, тестирование Worker v2, подпись и согласие владельца. Старый PR сохранён.
