// Сбор анонимной статистики Adult Space (только по согласию пользователя) и API для приложения-просмотрщика.
// IP-адреса не сохраняются: страна и регион берутся из метаданных Cloudflare и записываются грубо.
// Секреты: INGEST_KEY (зашит в приложении, лишь отсекает случайный мусор), ADMIN_TOKEN (только у владельца).

const MAX_BODY = 16 * 1024;
const ONLINE_MS = 150_000;           // «онлайн» — был сигнал не позже 2,5 минут назад
const MAX_SESSION_MS = 6 * 3600_000; // защита от кривых данных

const json = (o, status = 200) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store' } });
const eq = (a, b) => { // сравнение секретов за постоянное время
  if (typeof a !== 'string' || typeof b !== 'string' || a.length !== b.length) return false;
  let r = 0; for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i); return r === 0;
};

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const stub = env.STORE.get(env.STORE.idFromName('main'));
    if (req.method === 'POST' && url.pathname === '/v1/ping') {
      if (!eq(req.headers.get('x-key') || '', env.INGEST_KEY)) return json({ error: 'key' }, 401);
      const text = await req.text();
      if (text.length > MAX_BODY) return json({ error: 'size' }, 413);
      let b; try { b = JSON.parse(text); } catch { return json({ error: 'json' }, 400); }
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
    this.sql = state.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS installs(iid TEXT PRIMARY KEY, first_seen INTEGER, last_seen INTEGER, ver TEXT, os TEXT, dev TEXT,
        lang TEXT, country TEXT, region TEXT, net TEXT, total_ms INTEGER DEFAULT 0, sessions_n INTEGER DEFAULT 0, fg_since INTEGER DEFAULT 0);
      CREATE TABLE IF NOT EXISTS sessions(iid TEXT, a INTEGER, b INTEGER, PRIMARY KEY(iid, a));
      CREATE TABLE IF NOT EXISTS events(iid TEXT, day TEXT, name TEXT, n INTEGER, PRIMARY KEY(iid, day, name));
      CREATE INDEX IF NOT EXISTS sess_a ON sessions(a);
    `);
  }
  rows(q, ...args) { return [...this.sql.exec(q, ...args)]; }
  one(q, ...args) { return this.rows(q, ...args)[0] || {}; }

  async fetch(req) {
    const url = new URL(req.url);
    if (req.method === 'POST') return this.ping(JSON.parse(await req.text()));
    const del = req.method === 'DELETE' && url.pathname.match(/^\/installs\/([\w-]{8,64})$/);
    if (del) { for (const t of ['installs', 'sessions', 'events']) this.sql.exec(`DELETE FROM ${t} WHERE iid=?`, del[1]); return json({ ok: true }); }
    if (url.pathname === '/summary') return json(this.summary());
    if (url.pathname === '/installs') return json(this.installs(Math.min(+url.searchParams.get('limit') || 200, 500)));
    const m = url.pathname.match(/^\/installs\/([\w-]{8,64})$/);
    if (m) return json(this.detail(m[1]));
    return json({ error: 'not found' }, 404);
  }

  ping(b) {
    const iid = String(b.iid || '');
    if (!/^[\w-]{8,64}$/.test(iid)) return json({ error: 'iid' }, 400);
    const now = Date.now(), cut = s => String(s || '').slice(0, 64);
    this.sql.exec(`INSERT INTO installs(iid, first_seen, last_seen, ver, os, dev, lang, country, region, net) VALUES(?,?,?,?,?,?,?,?,?,?)
      ON CONFLICT(iid) DO UPDATE SET last_seen=?, ver=?, os=?, dev=?, lang=?, country=?, region=?, net=?`,
      iid, now, now, cut(b.ver), cut(b.os), cut(b.dev), cut(b.lang), b.geo.country, b.geo.region, cut(b.net),
      now, cut(b.ver), cut(b.os), cut(b.dev), cut(b.lang), b.geo.country, b.geo.region, cut(b.net));
    for (const s of (Array.isArray(b.sessions) ? b.sessions : []).slice(0, 50)) {
      const a = Math.floor(+s.a), e = Math.floor(+s.b);
      if (!(a > 1.6e12 && e > a && e - a <= MAX_SESSION_MS && e <= now + 60_000)) continue;
      this.sql.exec('INSERT OR IGNORE INTO sessions(iid, a, b) VALUES(?,?,?)', iid, a, e);
    }
    const ev = b.events && typeof b.events === 'object' ? Object.entries(b.events).slice(0, 30) : [];
    const day = new Date(now).toISOString().slice(0, 10);
    for (const [k, n] of ev) if (/^[\w.-]{1,32}$/.test(k) && n > 0 && n < 1e6)
      this.sql.exec('INSERT INTO events(iid, day, name, n) VALUES(?,?,?,?) ON CONFLICT(iid, day, name) DO UPDATE SET n = n + ?', iid, day, k, Math.floor(n), Math.floor(n));
    const t = this.one('SELECT COALESCE(SUM(b-a),0) ms, COUNT(*) n FROM sessions WHERE iid=?', iid);
    this.sql.exec('UPDATE installs SET total_ms=?, sessions_n=?, fg_since=? WHERE iid=?', t.ms, t.n, b.fg ? Math.floor(+b.fg) || now : 0, iid);
    return json({ ok: true });
  }

  summary() {
    const now = Date.now(), D = 86400_000;
    const days = [];
    for (let i = 13; i >= 0; i--) {
      const from = new Date(new Date(now - i * D).toISOString().slice(0, 10)).getTime(), to = from + D;
      const r = this.one('SELECT COUNT(*) sessions, COALESCE(SUM(b-a),0) ms, COUNT(DISTINCT iid) users FROM sessions WHERE a>=? AND a<?', from, to);
      days.push({ day: new Date(from).toISOString().slice(0, 10), ...r, newInstalls: this.one('SELECT COUNT(*) n FROM installs WHERE first_seen>=? AND first_seen<?', from, to).n });
    }
    const by = col => this.rows(`SELECT ${col} k, COUNT(*) n FROM installs GROUP BY ${col} ORDER BY n DESC LIMIT 15`);
    return {
      now,
      installs: this.one('SELECT COUNT(*) n FROM installs').n,
      online: this.one('SELECT COUNT(*) n FROM installs WHERE last_seen>?', now - ONLINE_MS).n,
      active24h: this.one('SELECT COUNT(*) n FROM installs WHERE last_seen>?', now - D).n,
      active7d: this.one('SELECT COUNT(*) n FROM installs WHERE last_seen>?', now - 7 * D).n,
      totalMs: this.one('SELECT COALESCE(SUM(total_ms),0) ms FROM installs').ms,
      countries: by('country'), devices: by('dev'), versions: by('ver'), langs: by('lang'), os: by('os'),
      events: this.rows('SELECT name k, SUM(n) n FROM events GROUP BY name ORDER BY n DESC LIMIT 20'),
      days,
    };
  }

  installs(limit) {
    const now = Date.now();
    return { now, onlineMs: ONLINE_MS, items: this.rows('SELECT * FROM installs ORDER BY last_seen DESC LIMIT ?', limit).map(r => ({ ...r, online: now - r.last_seen < ONLINE_MS })) };
  }

  detail(iid) {
    const inst = this.one('SELECT * FROM installs WHERE iid=?', iid);
    if (!inst.iid) return { error: 'not found' };
    return { install: inst, sessions: this.rows('SELECT a, b FROM sessions WHERE iid=? ORDER BY a DESC LIMIT 100', iid),
      events: this.rows('SELECT day, name, n FROM events WHERE iid=? ORDER BY day DESC LIMIT 100', iid) };
  }
}
