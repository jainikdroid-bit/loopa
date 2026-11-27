// Offline, generic ranking metrics for the bundled non-sensitive fixture.
export function metrics(ranked, grades, k) {
  if (!Number.isInteger(k) || k <= 0 || new Set(ranked).size !== ranked.length) throw Error("Invalid ranking");
  if (Object.values(grades).some(g => !Number.isInteger(g) || g < 0 || g > 3)) throw Error("Invalid grade");
  const top = ranked.slice(0, k);
  const relevant = Object.values(grades).filter(g => g > 0).length;
  const hits = top.filter(id => (grades[id] ?? 0) > 0).length;
  const dcg = values => values.reduce((sum, g, i) => sum + (2 ** g - 1) / Math.log2(i + 2), 0);
  const ideal = dcg(Object.values(grades).sort((a,b) => b-a).slice(0,k));
  return { precision: hits/k, recall: relevant ? hits/relevant : null,
    ndcg: ideal ? dcg(top.map(id => grades[id] ?? 0))/ideal : null };
}

export function compare(baseline, candidate, cases, k=10) {
  if (cases.length === 0) throw Error("No evaluation cases");
  const aggregate = ranking => {
    const rows = cases.map(c => metrics(ranking[c.id] ?? [], c.grades, k));
    return Object.fromEntries(["precision","recall","ndcg"].map(key => {
      const values = rows.map(row => row[key]).filter(v => v !== null);
      return [key, values.length ? values.reduce((a,b) => a+b,0)/values.length : null];
    }));
  };
  const before = aggregate(baseline), after = aggregate(candidate);
  const delta = before.ndcg === null || after.ndcg === null ? null : after.ndcg-before.ndcg;
  return { cases: cases.length, k, baseline: before, candidate: after, delta,
    regression: delta === null || delta < -0.01 };
}
