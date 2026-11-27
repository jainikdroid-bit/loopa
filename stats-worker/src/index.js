// Сбор анонимной статистики Adult Space (только по согласию пользователя) и API для приложения-просмотрщика.
// IP-адреса не сохраняются: страна и регион берутся из метаданных Cloudflare и записываются грубо.
// Секреты: INGEST_KEY (зашит в приложении, лишь отсекает случайный мусор), ADMIN_TOKEN (только у владельца).

const MAX_BODY = 16 * 1024;
const ONLINE_MS = 150_000;           // «онлайн» — был сигнал не позже 2,5 минут назад
const MAX_SESSION_MS = 6 * 3600_000; // защита от кривых данных
const MAX_V1_SESSIONS = 100;
const MAX_V2_SESSIONS = 50;
const DAY_MS = 86400_000;
const SUMMARY_WINDOWS = [7, 14, 30, 90];
// Имена совпадают с ключами Android Stats.Event. В v2 принимаются только счётчики.
const V2_EVENTS = new Set([
  'graph', 'player', 'download', 'search_submit', 'feed_foryou', 'feed_trending', 'feed_likes', 'feed_reddit', 'feed_other',
  'player_first_frame', 'player_error', 'player_start_lt_1s', 'player_start_1_3s', 'player_start_3_8s', 'player_start_ge_8s',
  'download_queued', 'download_saved', 'download_failed', 'settings_open', 'source_check_open',
  'search_page_results', 'search_page_empty', 'search_page_error', 'feed_load_error', 'player_stall_1s', 'player_auto_sd',
]);

const utcDay = ms => new Date(ms).toISOString().slice(0, 10);
const queryInteger = (value, fallback) => {
  const n = Number(value);
  return /^\d+$/.test(value) && Number.isSafeInteger(n) ? n : fallback;
};

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
const eq = (a, b) => { // сравнение секретов за постоянное время
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let r = 0; for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i); return r === 0;
};

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const stub = env.STORE.get(env.STORE.idFromName('main'));
    if (req.method === 'POST' && ['/v1/ping', '/v2/ping'].includes(url.pathname)) {
      if (!eq(req.headers.get('x-key') || '', env.INGEST_KEY)) return json({ error: 'key' }, 401);
      const text = await req.text();
      if (text.length > MAX_BODY) return json({ error: 'size' }, 413);
      let b; try { b = JSON.parse(text); } catch { return json({ error: 'json' }, 400); }
      if (!b || typeof b !== 'object' || Array.isArray(b)) return json({ error: 'json' }, 400);
      if (url.pathname === '/v2/ping' && (typeof b.batch !== 'string' || !/^[\w-]{8,64}$/.test(b.batch))) return json({ error: 'batch' }, 400);
      if (url.pathname === '/v1/ping') delete b.batch;
      const cf = req.cf || {};
      b.geo = { country: cf.country || '', region: cf.region || '' };
      return stub.fetch('https://do/ping', { method: 'POST', body: JSON.stringify(b) });
    }
    if (url.pathname.startsWith('/v1/admin/')) {
      const tok = (req.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
      if (!eq(tok, env.ADMIN_TOKEN)) return json({ error: 'auth' }, 401);
      return stub.fetch('https://do' + url.pathname.replace('/v1/admin', '') + url.search, { method: req.method === 'DELETE' ? 'DELETE' : 'GET' });
    }
    return json({ ok: true, name: 'loopa-stats' });
  },
};

export class Store {
  constructor(state) {
    this.storage = state.storage;
    this.sql = state.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS installs(iid TEXT PRIMARY KEY, first_seen INTEGER, last_seen INTEGER, ver TEXT, os TEXT, dev TEXT,
        lang TEXT, country TEXT, region TEXT, net TEXT, total_ms INTEGER DEFAULT 0, sessions_n INTEGER DEFAULT 0, fg_since INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS sessions(iid TEXT, a INTEGER, b INTEGER, PRIMARY KEY(iid, a));
      CREATE TABLE IF NOT EXISTS events(iid TEXT, day TEXT, name TEXT, n INTEGER, PRIMARY KEY(iid, day, name));
      CREATE INDEX IF NOT EXISTS sess_a ON sessions(a);
      CREATE TABLE IF NOT EXISTS batches(iid TEXT, batch TEXT, at INTEGER, PRIMARY KEY(iid, batch));
    `);
  }
  rows(q, ...args) { return [...this.sql.exec(q, ...args)]; }
  one(q, ...args) { return this.rows(q, ...args)[0] || {}; }

  async fetch(req) {
    const url = new URL(req.url);
    if (req.method === 'POST') return this.ping(JSON.parse(await req.text()));
    const del = req.method === 'DELETE' && url.pathname.match(/^\/installs\/([\w-]{8,64})$/);
    if (del) { for (const t of ['installs', 'sessions', 'events', 'batches']) this.sql.exec(`DELETE FROM ${t} WHERE iid=?`, del[1]); return json({ ok: true }); }
    if (url.pathname === '/summary') {
      const days = queryInteger(url.searchParams.get('days'), 14);
      return json(this.summary(SUMMARY_WINDOWS.includes(days) ? days : 14));
    }
    if (url.pathname === '/installs') {
      const limit = Math.min(queryInteger(url.searchParams.get('limit'), 200) || 200, 500);
      const offset = queryInteger(url.searchParams.get('offset'), 0);
      return json(this.installs(limit, offset));
    }
    const m = url.pathname.match(/^\/installs\/([\w-]{8,64})$/);
    if (m) return json(this.detail(m[1]));
    return json({ error: 'not found' }, 404);
  }

  ping(b) {
    const iid = String(b.iid || '');
    if (!/^[\w-]{8,64}$/.test(iid)) return json({ error: 'iid' }, 400);
    const batch = typeof b.batch === 'string' ? b.batch : '';
    const events = b.events && typeof b.events === 'object' ? Object.entries(b.events) : [];
    const counters = batch
      ? events.filter(([name, n]) => V2_EVENTS.has(name) && Number.isInteger(n) && n > 0 && n < 1e6)
      : events.slice(0, 30).filter(([name, n]) => /^[\w.-]{1,32}$/.test(name) && n > 0 && n < 1e6);
    return this.storage.transactionSync(() => {
      // Heartbeats and session-only requests are naturally idempotent. Keep batch receipts
      // only for non-idempotent event-counter increments.
      const recordBatch = batch && counters.length > 0;
      if (recordBatch && this.one('SELECT batch FROM batches WHERE iid=? AND batch=?', iid, batch).batch) return json({ ok: true, batch });
      this.applyPing(b, iid, counters, batch ? MAX_V2_SESSIONS : MAX_V1_SESSIONS);
      if (recordBatch) this.sql.exec('INSERT INTO batches(iid,batch,at) VALUES(?,?,?)', iid, batch, Date.now());
      return json(batch ? { ok: true, batch } : { ok: true });
    });
  }

  applyPing(b, iid, counters, sessionLimit) {
    const now = Date.now(), cut = s => String(s || '').slice(0, 64);
    this.sql.exec(`INSERT INTO installs(iid, first_seen, last_seen, ver, os, dev, lang, country, region, net) VALUES(?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(iid) DO UPDATE SET last_seen=?, ver=?, os=?, dev=?, lang=?, country=?, region=?, net=?`,
      iid, now, now, cut(b.ver), cut(b.os), cut(b.dev), cut(b.lang), cut(b.geo?.country), cut(b.geo?.region), cut(b.net),
      now, cut(b.ver), cut(b.os), cut(b.dev), cut(b.lang), cut(b.geo?.country), cut(b.geo?.region), cut(b.net));
    for (const s of (Array.isArray(b.sessions) ? b.sessions : []).slice(0, sessionLimit)) {
      if (!s || typeof s !== 'object') continue;
      const a = Math.floor(+s.a), e = Math.floor(+s.b);
      if (!(a > 1.6e12 && e > a && e - a <= MAX_SESSION_MS && e <= now + 60_000)) continue;
      this.sql.exec('INSERT OR IGNORE INTO sessions(iid, a, b) VALUES(?,?,?)', iid, a, e);
    }
    const day = utcDay(now);
    for (const [name, n] of counters) {
      const count = Math.floor(n);
      this.sql.exec('INSERT INTO events(iid, day, name, n) VALUES(?,?,?,?) ON CONFLICT(iid, day, name) DO UPDATE SET n = n + ?', iid, day, name, count, count);
    }
    const t = this.one('SELECT COALESCE(SUM(b-a),0) ms, COUNT(*) n FROM sessions WHERE iid=?', iid);
    this.sql.exec('UPDATE installs SET total_ms=?, sessions_n=?, fg_since=? WHERE iid=?', t.ms, t.n, b.fg ? Math.floor(+b.fg) || now : 0, iid);
  }

  summary(dayCount = 14) {
    const now = Date.now();
    const end = (Math.floor(now / DAY_MS) + 1) * DAY_MS;
    const start = end - dayCount * DAY_MS;
    const sessionCounts = 'COUNT(*) sessions, COALESCE(SUM(b-a),0) ms, COUNT(DISTINCT iid) users';
    const sessionsByDay = new Map(this.rows(`
      SELECT date(a / 1000, 'unixepoch') day, ${sessionCounts}
      FROM sessions WHERE a>=? AND a<? GROUP BY day
    `, start, end).map(row => [row.day, row]));
    const installsByDay = new Map(this.rows(`
      SELECT date(first_seen / 1000, 'unixepoch') day, COUNT(*) n
      FROM installs WHERE first_seen>=? AND first_seen<? GROUP BY day
    `, start, end).map(row => [row.day, row.n]));

    const days = [];
    let newInstalls = 0;
    for (let from = start; from < end; from += DAY_MS) {
      const day = utcDay(from);
      const { sessions = 0, ms = 0, users = 0 } = sessionsByDay.get(day) ?? {};
      const n = installsByDay.get(day) ?? 0;
      days.push({ day, sessions, ms, users, newInstalls: n });
      newInstalls += n;
    }
    const period = {
      ...this.one(`SELECT ${sessionCounts} FROM sessions WHERE a>=? AND a<?`, start, end),
      newInstalls,
      events: this.rows('SELECT name k, SUM(n) n FROM events WHERE day>=? AND day<? GROUP BY name ORDER BY n DESC, k ASC', utcDay(start), utcDay(end)),
    };
    const by = col => this.rows(`SELECT ${col} k, COUNT(*) n FROM installs GROUP BY ${col} ORDER BY n DESC LIMIT 15`);
    const eventTotals = this.rows('SELECT name k, SUM(n) n FROM events GROUP BY name ORDER BY n DESC, k ASC');
    return {
      now,
      installs: this.one('SELECT COUNT(*) n FROM installs').n,
      online: this.one('SELECT COUNT(*) n FROM installs WHERE last_seen>?', now - ONLINE_MS).n,
      active24h: this.one('SELECT COUNT(*) n FROM installs WHERE last_seen>?', now - DAY_MS).n,
      active7d: this.one('SELECT COUNT(*) n FROM installs WHERE last_seen>?', now - 7 * DAY_MS).n,
      active30d: this.one('SELECT COUNT(*) n FROM installs WHERE last_seen>?', now - 30 * DAY_MS).n,
      totalMs: this.one('SELECT COALESCE(SUM(total_ms),0) ms FROM installs').ms,
      countries: by('country'), devices: by('dev'), versions: by('ver'), langs: by('lang'), os: by('os'),
      events: eventTotals.slice(0, 20),
      eventTotals,
      days,
      period,
    };
  }

  installs(limit, offset = 0) {
    const now = Date.now();
    return {
      now,
      onlineMs: ONLINE_MS,
      items: this.rows('SELECT * FROM installs ORDER BY last_seen DESC, iid ASC LIMIT ? OFFSET ?', limit, offset)
        .map(r => ({ ...r, online: now - r.last_seen < ONLINE_MS })),
      total: this.one('SELECT COUNT(*) n FROM installs').n,
    };
  }

  detail(iid) {
    const inst = this.one('SELECT * FROM installs WHERE iid=?', iid);
    if (!inst.iid) return { error: 'not found' };
    return { install: inst, sessions: this.rows('SELECT a, b FROM sessions WHERE iid=? ORDER BY a DESC LIMIT 100', iid),
      events: this.rows('SELECT day, name, n FROM events WHERE iid=? ORDER BY day DESC LIMIT 100', iid) };
  }
}
