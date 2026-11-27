import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { Store } from '../src/index.js';

function store() {
  const db = new DatabaseSync(':memory:');
  const sql = { exec(q, ...args) {
    if (!args.length && q.trim().startsWith('CREATE TABLE')) { db.exec(q); return []; }
    const stmt = db.prepare(q);
    if (/^\s*SELECT/i.test(q)) return stmt.all(...args);
    stmt.run(...args);
    return [];
  }};
  const storage = { sql, transactionSync(fn) {
    db.exec('BEGIN');
    try { const result = fn(); db.exec('COMMIT'); return result; }
    catch (e) { db.exec('ROLLBACK'); throw e; }
  }};
  return new Store({ storage });
}
test('batch retry cannot double count events', async () => {
  const s = store();
  const body = { iid: 'test-install', batch: 'batch-001', geo: {}, events: { player_error: 3 }, sessions: [] };
  const first = await s.ping(body).json();
  assert.equal(first.batch, body.batch);
  await s.ping(body).json();
  assert.equal(s.detail(body.iid).events[0].n, 3);
  await s.ping({ ...body, batch: 'batch-002' }).json();
  assert.equal(s.detail(body.iid).events[0].n, 6);
});
test('50 + 50 sessions are retained and retries are idempotent', async () => {
  const s = store();
  const now = Date.now() - 1000000;
  const sessions = Array.from({ length: 100 }, (_, i) => ({ a: now + i * 10000, b: now + i * 10000 + 5000 }));
  const body = { iid: 'test-install', batch: 'batch-001', geo: {}, events: {}, sessions: sessions.slice(0,50) };
  await s.ping(body).json();
  await s.ping(body).json();
  await s.ping({ ...body, batch: 'batch-002', sessions: sessions.slice(50) }).json();
  assert.equal(s.detail(body.iid).install.sessions_n, 100);
  assert.equal(s.detail(body.iid).install.total_ms, 500000);
});
test('admin deletion includes receipt rows', async () => {
  const s = store();
  await s.ping({ iid: 'test-install', batch: 'batch-001', geo: {}, events: { player: 1 } }).json();
  assert.equal(s.one('SELECT COUNT(*) n FROM batches').n, 1);
  await s.fetch(new Request('https://do/installs/test-install', { method: 'DELETE' }));
  assert.equal(s.one('SELECT COUNT(*) n FROM batches').n, 0);
  assert.equal(s.detail('test-install').error, 'not found');
});
