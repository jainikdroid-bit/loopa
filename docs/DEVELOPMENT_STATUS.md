# Development status / Статус разработки

Snapshot: 2026-11-26. Published release: v1.34 (2026-11-25). Development branch: prepare/claude-release-training; draft PR #2. No release version assigned or APK published from this branch.

| Area | Development status |
|---|---|
| Local data | Separate opt-in diagnostics; bounded journal and explicit technical-counter export |
| Telemetry | Explicit opt-in, durable batches; server deployment not verified |
| UI | Configurable portrait/landscape column counts, aspect ratio and tile margin; prior defaults retained |
| Lifecycle | Cancellation and resource cleanup changes; conservative rollback of broad thumbnail rewrite |
| Updates | Background installer preparation, APK package/version/digest verification; earlier cached installers without digest require download again |
| Build | No implicit debug signing for release; owner key required for production |
| Testing | Android unit tests, lint and debug build passed on grid settings commit 4138689 (run 45711230498); newer update commits require their own verification |

No measured FPS or weak-network benchmark on a physical device, no proof of model relevance improvement, no new external adult data import, no release signing or deployment. See [handoff](TRAINING_RELEASE_HANDOFF.md) for technical checks. Treat any draft feature descriptions as unreleased.

## По-русски

Опубликована версия 1.34. Настройки внешнего вида сетки и исправления приватности, жизненного цикла и обновлений подготовлены в отдельной ветке. Результаты CI не заменяют испытания на телефоне. Перед публикацией нужно проверить новые изменения, миграцию, согласие на статистику, обновление поверх старой версии и подпись владельца. 
