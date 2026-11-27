# Neutral offline ranking evaluation

Uses four hand-labelled neutral examples and two explicitly fixed ranking lists. It tests metrics and an experimental version/rollback gate, not search quality of the Android app or a trained model. No network, production catalog, personal data, or production checkpoint integration.

```
node --test tools/neutral-lab/*.test.mjs
node tools/neutral-lab/run.mjs
```

NDCG uses graded gains; precision has denominator k, recall uses all relevant labels. Unjudged items count as zero for this fully specified fixture; do not assume this for sparse labels. The fixed example is deliberately simple; the results cannot establish generalization. Checkpoint records contain reports, not trained weights. The 0.01 NDCG tolerance is a test convention, not a validated release threshold.

Not implemented: production BM25/fuzzy/semantic ranking, real multilingual lemmatization, trained ranking or RRF fusion, production personalization model changes, live rollback or new recommendation training. Do not connect this fixture to live sources. Validate algorithm research on larger licensed non-sensitive corpora.

## Expanded data fixture
`data/synthetic-v2.json` contains 72 original educational document cards, 36 queries in English/Russian/Polish, 216 graded query/document pairs, and 12 neutral tag groups. The labels are generated from manually selected topics; they do not establish linguistic or search quality. `data.test.mjs` verifies IDs, labels and metric consistency. No use by Android runtime.

`data/sources.json` records public research dataset provenance, licensing, and status. Only SciFact has an optional audited download path; no external dataset is included in the repo or APK. The other references are not implemented imports.

```sh
node --test tools/neutral-lab/*.test.mjs
python3 -m unittest discover -s tools/neutral-lab -p 'test_*.py'
python3 tools/neutral-lab/scifact_audit.py /tmp/scifact.tar.gz --download
```

The last command explicitly downloads the upstream scientific archive, pins the published SHA-256, checks archive members without extracting files, and outputs only aggregate counts. Run locally only after reviewing upstream attribution/usage terms. The archive could not be downloaded in the editing environment (DNS resolution failed); the parser is tested with a synthetic archive, not the actual downloaded file.
