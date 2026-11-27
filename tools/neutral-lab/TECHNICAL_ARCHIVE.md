# Local technical-report archive

A standard-library-only Python CLI for the application's explicit technical diagnostic export (schema 1, fixed 30-day window). Imports only eight coarse operational counters. It does not read the app database or import content records, tags, identities, preferences, queries or model parameters. No training or production ranking integration.

Supported inputs:
- JSON from Settings > Export technical diagnostics.
- CSV with exactly the header kind,count and one row per allowed technical category.
- A standalone, ordinary SQLite table technical_counts(kind,count); do not use a live/WAL database.

Examples:

```sh
python3 tools/neutral-lab/technical_archive.py --store ~/.loopa-tech import report.json
python3 tools/neutral-lab/technical_archive.py --store ~/.loopa-tech list
python3 tools/neutral-lab/technical_archive.py --store ~/.loopa-tech show REPORT_SHA256
python3 tools/neutral-lab/technical_archive.py --store ~/.loopa-tech delete REPORT_SHA256
python3 -m unittest discover -s tools/neutral-lab -p 'test_*.py'
```

Reports are normalized, deduplicated by SHA-256, capped at 256 KiB input / 10,000 total events / 1,000 archived reports. On POSIX the archive directory must be private (0700); archive entries are create-only. Imports reject unknown fields, duplicate categories, bad numbers, and unexpected SQLite schemas. No network or APK changes.

Identical aggregate counts from different dates are intentionally deduplicated; these snapshots have no dates or user IDs. Do not sum overlapping windows or use this archive as a longitudinal user tracker. File deletion is ordinary filesystem removal, not secure erasure. Run only in a trusted local directory; external concurrent writers and untrusted filesystem manipulation are outside scope. Windows ACLs are not managed by this tool. Keep real exported reports outside Git.
