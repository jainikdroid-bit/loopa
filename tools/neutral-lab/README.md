# Neutral offline ranking evaluation

Uses four hand-labelled neutral examples and two explicitly fixed ranking lists. It tests metrics and an experimental version/rollback gate, not search quality of the Android app or a trained model. No network, production catalog, personal data, or production checkpoint integration.

```
node --test tools/neutral-lab/*.test.mjs
node tools/neutral-lab/run.mjs
```

NDCG uses graded gains; precision has denominator k, recall uses all relevant labels. Unjudged items count as zero for this fully specified fixture; do not assume this for sparse labels. The fixed example is deliberately simple; the results cannot establish generalization. Checkpoint records contain reports, not trained weights. The 0.01 NDCG tolerance is a test convention, not a validated release threshold.

Not implemented: production BM25/fuzzy/semantic ranking, real multilingual lemmatization, trained ranking or RRF fusion, production personalization model changes, live rollback or new recommendation training. Do not connect this fixture to live sources. Validate algorithm research on larger licensed non-sensitive corpora.
