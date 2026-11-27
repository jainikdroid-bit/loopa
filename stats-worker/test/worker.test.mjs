import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { test } from 'node:test';
import worker, { Store } from '../src/index.js';

const DAY = 86_400_000;
const MIDNIGHT = Date.parse('2026-11-27T00:00:00Z');
const NOW = MIDNIGHT + DAY / 2;
const day = ago => new Date(MIDNIGHT - ago * DAY).toISOString().slice(0, 10);

// Схема v1 из Worker до появления периодов и пагинации.
const legacySchema = `
  CREATE TABLE installs(iid TEXT PRIMARY KEY, first_seen INTEGER, last_seen INTEGER, ver TEXT, os TEXT, dev TEXT,
    lang TEXT, country TEXT, region TEXT, net TEXT, total_ms INTEGER DEFAULT 0, sessions_n INTEGER DEFAULT 0, fg_since INTEGER DEFAULT 0);
  CREATE TABLE sessions(iid TEXT, a INTEGER, b INTEGER, PRIMARY KEY(iid, a));
  CREATE TABLE events(iid TEXT, day TEXT, name TEXT, n INTEGER, PRIMARY KEY(iid, day, name));
  CREATE INDEX sess_a ON sessions(a);
`;

function fixture(t) {
  t.mock.method(Date, 'now', () => NOW);
  const db = new DatabaseSync(':memory:');
  t.after(() => db.close());
  db.exec(legacySchema);

  const addInstall = db.prepare(`
    INSERT INTO installs(iid, first_seen, last_seen, ver, os, dev, lang, country, region, net, total_ms, sessions_n, fg_since)
    VALUES (?, ?, ?, '1.33', 'Android 15', 'Samsung', 'ru', 'RU', 'Moscow', 'wifi', ?, ?, 0)
  `);
  addInstall.run('install-b', MIDNIGHT - 29 * DAY, NOW - 60_000, 632, 2);
  addInstall.run('install-a', MIDNIGHT - 100 * DAY, NOW - 60_000, 1600, 9);
  addInstall.run('install-c', MIDNIGHT - 6 * DAY, NOW - 2 * DAY, 1000, 1);
  addInstall.run('install-d', MIDNIGHT - 89 * DAY, NOW - 31 * DAY, 310, 1);
  addInstall.run('install-e', MIDNIGHT - 13 * DAY, NOW - 10 * DAY, 0, 0);

  const addSession = db.prepare('INSERT INTO sessions(iid, a, b) VALUES (?, ?, ?)');
  for (const [ago, duration] of [[90, 100], [89, 110], [30, 200], [29, 290], [14, 140], [13, 130], [7, 70], [6, 60], [0, 500]]) {
    const a = MIDNIGHT - ago * DAY;
    addSession.run('install-a', a, a + duration);
  }
  addSession.run('install-b', MIDNIGHT - 13 * DAY, MIDNIGHT - 13 * DAY + 131);
  addSession.run('install-b', MIDNIGHT, MIDNIGHT + 501);
  addSession.run('install-c', MIDNIGHT - 200, MIDNIGHT + 800);
  addSession.run('install-d', MIDNIGHT - 31 * DAY, MIDNIGHT - 31 * DAY + 310);

  const addEvent = db.prepare('INSERT INTO events(iid, day, name, n) VALUES (?, ?, ?, ?)');
  for (const [ago, count] of [[90, 32], [89, 16], [29, 8], [13, 4], [6, 2]]) {
    addEvent.run('install-a', day(ago), 'play', count);
  }
  addEvent.run('install-b', day(0), 'play', 1);
  addEvent.run('install-a', day(13), 'view', 4);
  addEvent.run('install-c', day(0), 'view', 1);
  addEvent.run('install-a', day(100), 'old-only', 20);

  const snapshot = () => ({
    schema: db.prepare("SELECT name, sql FROM sqlite_master WHERE tbl_name <> 'batches' ORDER BY name").all(),
    installs: db.prepare('SELECT * FROM installs ORDER BY iid').all(),
    sessions: db.prepare('SELECT * FROM sessions ORDER BY iid, a').all(),
    events: db.prepare('SELECT * FROM events ORDER BY iid, day, name').all(),
  });
  const before = snapshot();
  const store = new Store({ storage: {
    sql: {
      exec(sql, ...args) {
        // Адаптер возвращает строки так же, как курсор Durable Object SQL.
        if (sql.trimStart().startsWith('CREATE TABLE')) {
          db.exec(sql);
          return [];
        }
        return db.prepare(sql).all(...args);
      },
    },
    transactionSync(fn) {
      db.exec('BEGIN');
      try {
        const result = fn();
        db.exec('COMMIT');
        return result;
      } catch (error) {
        db.exec('ROLLBACK');
        throw error;
      }
    },
  } });
  const env = {
    INGEST_KEY: 'fixture-ingest',
    ADMIN_TOKEN: 'fixture-admin',
    STORE: {
      idFromName(name) {
        assert.equal(name, 'main');
        return name;
      },
      get(id) {
        assert.equal(id, 'main');
        return { fetch: (url, init) => store.fetch(new Request(url, init)) };
      },
    },
  };

  async function admin(path) {
    const res = await worker.fetch(new Request('https://fixture.test/v1/admin' + path, {
      headers: { authorization: 'Bearer fixture-admin' },
    }), env);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('cache-control'), 'no-store');
    return res.json();
  }

  async function ping(body, version = 'v1') {
    const req = new Request(`https://fixture.test/${version}/ping`, {
      method: 'POST',
      headers: { 'x-key': 'fixture-ingest' },
      body: JSON.stringify(body),
    });
    req.cf = { country: 'US', region: 'California' };
    return worker.fetch(req, env);
  }

  return { admin, ping, db, snapshot, before };
}

test('legacy summary and all UTC periods include history from the v1 tables', async t => {
  const { admin, snapshot, before } = fixture(t);
  const legacy = await admin('/summary');
  assert.deepEqual(legacy, await admin('/summary?days=14'));
  assert.deepEqual(legacy, await admin('/summary?days=8'));
  assert.deepEqual(legacy, await admin('/summary?days=-7'));
  const { days, period, eventTotals, ...allTime } = legacy;
  assert.deepEqual(allTime, {
    now: NOW, installs: 5, online: 2, active24h: 2, active7d: 3, active30d: 4, totalMs: 3542,
    countries: [{ k: 'RU', n: 5 }], devices: [{ k: 'Samsung', n: 5 }],
    versions: [{ k: '1.33', n: 5 }], langs: [{ k: 'ru', n: 5 }], os: [{ k: 'Android 15', n: 5 }],
    events: [{ k: 'play', n: 63 }, { k: 'old-only', n: 20 }, { k: 'view', n: 5 }],
  });
  assert.deepEqual(eventTotals, allTime.events);

  for (const [count, sessions, users, ms, newInstalls, plays, views] of [
    [7, 4, 3, 2061, 1, 3, 1],
    [14, 7, 3, 2392, 2, 7, 5],
    [30, 9, 3, 2822, 3, 15, 5],
    [90, 12, 4, 3442, 4, 31, 5],
  ]) {
    const summary = await admin('/summary?days=' + count);
    assert.deepEqual(summary.days.map(row => row.day), Array.from({ length: count }, (_, i) => day(count - 1 - i)));
    assert.notEqual(summary.days[0].sessions, 0);
    assert.deepEqual(summary.period, {
      sessions, users, ms, newInstalls, events: [{ k: 'play', n: plays }, { k: 'view', n: views }],
    });
    assert.deepEqual(summary.events, allTime.events);
  }
  assert.deepEqual(days[0], { day: day(13), sessions: 2, users: 2, ms: 261, newInstalls: 1 });
  assert.deepEqual(days.at(-3), { day: day(2), sessions: 0, users: 0, ms: 0, newInstalls: 0 });
  assert.deepEqual(days.at(-2), { day: day(1), sessions: 1, users: 1, ms: 1000, newInstalls: 0 });
  assert.deepEqual(days.at(-1), { day: day(0), sessions: 2, users: 2, ms: 1001, newInstalls: 0 });
  assert.equal(period.users, 3);
  assert.deepEqual(snapshot(), before);
});

test('all-time event totals include legacy and new counters beyond the old top 20', async t => {
  const { admin, db, snapshot } = fixture(t);
  const insert = db.prepare('INSERT INTO events(iid, day, name, n) VALUES(?, ?, ?, ?)');
  for (let i = 0; i < 25; i++) insert.run('extra-fixture', day(0), 'counter-' + i, 100 + i);
  const before = snapshot();
  const summary = await admin('/summary?days=7');
  assert.equal(summary.events.length, 20);
  assert.equal(summary.eventTotals.length, 28);
  assert.deepEqual(summary.eventTotals.find(row => row.k === 'old-only'), { k: 'old-only', n: 20 });
  assert.deepEqual(snapshot(), before);
});

test('installs retain the legacy payload and paginate ties by iid', async t => {
  const { admin, db, before } = fixture(t);
  const legacy = await admin('/installs');
  const expectedOrder = ['install-a', 'install-b', 'install-c', 'install-e', 'install-d'];
  assert.equal(legacy.now, NOW);
  assert.equal(legacy.onlineMs, 150_000);
  assert.equal(legacy.total, 5);
  assert.deepEqual(legacy.items.map(row => row.iid), expectedOrder);
  assert.deepEqual(legacy.items[0], { ...before.installs[0], online: true });
  assert.deepEqual(legacy.items.map(row => row.online), [true, true, false, false, false]);

  const paginated = [];
  for (const offset of [0, 1, 2, 3, 4, 5]) {
    const page = await admin('/installs?limit=1&offset=' + offset);
    assert.equal(page.total, 5);
    paginated.push(...page.items.map(row => row.iid));
  }
  assert.deepEqual(paginated, expectedOrder);
  assert.deepEqual((await admin('/installs?offset=3')).items, legacy.items.slice(3));
  assert.deepEqual(await admin('/installs?limit=0&offset=-1'), legacy);

  const insert = db.prepare('INSERT INTO installs(iid, first_seen, last_seen) VALUES (?, ?, ?)');
  for (let i = 0; i < 505; i++) insert.run('extra-' + i, MIDNIGHT, MIDNIGHT);
  const capped = await admin('/installs?limit=900');
  assert.equal(capped.total, 510);
  assert.equal(capped.items.length, 500);
  assert.equal((await admin('/installs')).items.length, 200);
  assert.equal((await admin('/installs?limit=900&offset=500')).items.length, 10);
});

test('legacy pings store sessions 51 through 100, deduplicate sessions and add event counts', async t => {
  const { admin, ping } = fixture(t);
  const sessions = Array.from({ length: 125 }, (_, i) => {
    const a = NOW - 610_000 + i * 4_000;
    return { a, b: a + 3_000 };
  });
  const client = { iid: 'install-new', batch: 'legacy-batch', ver: '1.33', os: 'Android 15', dev: 'Samsung', lang: 'en', net: 'wifi' };
  const first = await ping({ ...client, fg: NOW - 5000, sessions: sessions.slice(0, 100), events: { play: 4, view: 1 } });
  assert.equal(first.status, 200);
  assert.deepEqual(await first.json(), { ok: true });
  const detail = await admin('/installs/install-new');
  assert.deepEqual(detail.sessions, sessions.slice(0, 100).reverse());
  assert.deepEqual(detail.install, {
    iid: client.iid, first_seen: NOW, last_seen: NOW, ver: client.ver, os: client.os, dev: client.dev,
    lang: client.lang, country: 'US', region: 'California', net: client.net,
    total_ms: 300_000, sessions_n: 100, fg_since: NOW - 5000,
  });
  assert.deepEqual(detail.events.sort((a, b) => a.name.localeCompare(b.name)), [
    { day: day(0), name: 'play', n: 4 }, { day: day(0), name: 'view', n: 1 },
  ]);

  const second = await ping({ ...client, fg: 0, sessions: sessions.slice(75), events: { play: 2 } });
  assert.equal(second.status, 200);
  assert.deepEqual(await second.json(), { ok: true });
  const updated = await admin('/installs/install-new');
  assert.equal(updated.install.sessions_n, 125);
  assert.equal(updated.install.total_ms, 375_000);
  assert.equal(updated.install.fg_since, 0);
  assert.deepEqual(updated.sessions, sessions.slice(-100).reverse());
  assert.equal(updated.events.find(event => event.name === 'play').n, 6);
  assert.deepEqual((await admin('/summary?days=7')).period, {
    sessions: 129, users: 4, ms: 377_061, newInstalls: 2,
    events: [{ k: 'play', n: 9 }, { k: 'view', n: 2 }],
  });
});

test('ping retains the body guard and caps session batches at 100', async t => {
  const { admin, ping, snapshot, before } = fixture(t);
  const tooBig = await ping({ iid: 'body-limit', padding: 'x'.repeat(16 * 1024), events: { play: 1 } });
  assert.equal(tooBig.status, 413);
  assert.deepEqual(await tooBig.json(), { error: 'size' });
  assert.deepEqual(snapshot(), before);

  const sessions = Array.from({ length: 101 }, (_, i) => ({ a: NOW - 1000 + i, b: NOW + i }));
  const response = await ping({ iid: 'batch-limit', sessions });
  assert.equal(response.status, 200);
  const detail = await admin('/installs/batch-limit');
  assert.equal(detail.install.sessions_n, 100);
  assert.deepEqual(detail.sessions, sessions.slice(0, 100).reverse());
});

test('v2 accepts all Android counters once per batch and filters other event data', async t => {
  const { admin, db, ping } = fixture(t);
  const keys = [
    'graph', 'player', 'download', 'search_submit', 'feed_foryou', 'feed_trending', 'feed_likes', 'feed_reddit', 'feed_other',
    'player_first_frame', 'player_error', 'player_start_lt_1s', 'player_start_1_3s', 'player_start_3_8s', 'player_start_ge_8s',
    'download_queued', 'download_saved', 'download_failed', 'settings_open', 'source_check_open',
    'search_page_results', 'search_page_empty', 'search_page_error', 'feed_load_error', 'player_stall_1s', 'player_auto_sd',
  ];
  const iid = 'v2-install', batch = 'v2-new-signals';
  const missingBatch = await ping({ iid, events: { player: 1 } }, 'v2');
  assert.equal(missingBatch.status, 400);
  assert.deepEqual(await missingBatch.json(), { error: 'batch' });
  assert.deepEqual(await admin(`/installs/${iid}`), { error: 'not found' });

  const events = { ...Object.fromEntries(keys.map(key => [key, 2])), unknown_event: 3 };
  for (let retry = 0; retry < 2; retry++) {
    const res = await ping({ iid, batch, events }, 'v2');
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { ok: true, batch });
  }
  const invalid = await ping({ iid, batch: 'v2-invalid-counts', events: {
    player: { count: 2 }, graph: '2', download: 2.5,
  } }, 'v2');
  assert.equal(invalid.status, 200);
  assert.equal(db.prepare('SELECT COUNT(*) n FROM batches WHERE iid=?').get(iid).n, 1);

  const detail = await admin(`/installs/${iid}`);
  assert.deepEqual(detail.events.sort((a, b) => a.name.localeCompare(b.name)),
    keys.sort((a, b) => a.localeCompare(b)).map(name => ({ day: day(0), name, n: 2 })));
});
