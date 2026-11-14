// Экран «3D-граф» Loopa: сеть вкуса (теги, авторы, сабреддиты, слова, запросы, сайты) в стиле graphify.
// Данные и подписи даёт Kotlin (Loopa.data / Loopa.texts); id узла — «тип:термин».
(function () {
  'use strict';
  var $ = function (id) { return document.getElementById(id); };
  var T = {}, D = { nodes: [], links: [], communities: [] };
  try { T = JSON.parse(Loopa.texts()); } catch (e) { /* подписей нет — страница покажет пустые */ }
  try { D = JSON.parse(Loopa.data()); } catch (e) { $('empty').textContent = T.error || 'Error'; $('empty').style.display = 'flex'; return; }

  var LABELS = 30;                       // подписей у самых тяжёлых видимых узлов
  var MAX_LINKS = 3000;                  // сильнее порога рисуем не больше стольких связей (на 2000 узлах иначе тормозит)
  var TYPES = ['tag', 'user', 'sub', 'kw', 'q', 'site'];
  var PREFIX = { tag: '#', user: '@', sub: 'r/', kw: '', q: '', site: '' };
  var nodes = D.nodes, comms = D.communities;
  var typeOn = {};                       // какие типы узлов показаны
  TYPES.forEach(function (t) { typeOn[t] = true; });
  var TT = T.types || {}, T1 = T.type1 || {}, SITES = T.sites || {};
  /** Подпись узла по типу: #тег, @автор, r/сабреддит, слово, «запрос», название сайта. */
  function lab(t, name) {
    if (t === 'q') return '\u00ab' + name + '\u00bb';
    if (t === 'site') return SITES[name] || name;
    return (PREFIX[t] || '') + name;
  }
  function labOf(n) { return lab(n.t, n.name); }
  function vis(n) { return typeOn[n.t] !== false; }
  var byId = {}, nb = {};                // узел по имени; соседи: имя -> [{id, w}]
  nodes.forEach(function (n) { byId[n.id] = n; nb[n.id] = []; });
  var maxAct = 0.001, maxW = 0.001;
  /** Опорный вес для размера: у третьего по тяжести — один «сайт» с огромным весом иначе сделает всех остальных точками. */
  function calcRef() {
    var a = nodes.map(function (n) { return n.act; }).sort(function (x, y) { return y - x; });
    maxAct = Math.max(a[Math.min(2, a.length - 1)] || 0, 0.001);
  }
  calcRef();
  var linkThr = 0;                       // слабее — не рисуем
  D.links.forEach(function (l) {
    if (l.w > maxW) maxW = l.w;
    nb[l.source].push({ id: l.target, w: l.w }); nb[l.target].push({ id: l.source, w: l.w });
  });
  if (D.links.length > MAX_LINKS) linkThr = D.links.map(function (l) { return l.w; }).sort(function (x, y) { return y - x; })[MAX_LINKS - 1];
  Object.keys(nb).forEach(function (k) { nb[k].sort(function (a, b) { return b.w - a.w; }); });

  function stats() {
    var vn = nodes.filter(vis).length, vl = D.links.filter(function (l) { return vis(byId[l.source]) && vis(byId[l.target]); }).length;
    return (T.stats || '%s / %s').replace('%s', vn).replace('%s', vl);
  }
  $('q').placeholder = T.search || '';
  $('open').textContent = T.open || '';
  if (!nodes.length) { $('empty').textContent = T.empty || ''; $('empty').style.display = 'flex'; $('q').style.display = 'none'; return; }
  $('stat').textContent = stats();

  var sel = null, comm = -1, found = null;   // выбранный тег, выбранное сообщество, найденные поиском
  var focus = null;                           // множество подсвеченных id (null — всё как есть)

  // размер — по весу: радиус сферы растёт как вес (библиотека берёт объём, поэтому куб)
  function nodeVal(n) { return Math.pow(0.45 + 2.6 * Math.min(Math.max(n.act, 0) / maxAct, 1.15), 3); }
  function fontSize(id) { return (10 + 6 * Math.min(Math.max(byId[id].act, 0) / maxAct, 1)).toFixed(1) + 'px'; }
  function rgba(hex, a) {
    return 'rgba(' + parseInt(hex.substr(1, 2), 16) + ',' + parseInt(hex.substr(3, 2), 16) + ',' + parseInt(hex.substr(5, 2), 16) + ',' + a + ')';
  }
  function nodeColor(n) {
    var a = n.suggested ? 0.5 : 1;                     // «рядом с любимыми» — полупрозрачные
    if (focus && !focus[n.id]) a = 0.1;
    return rgba(comms[n.c].color, a);
  }
  function endId(e) { return typeof e === 'object' ? e.id : e; }
  function linkOn(l) { return sel && (endId(l.source) === sel || endId(l.target) === sel); }
  function linkColor(l) {
    if (linkOn(l)) return 'rgba(255,255,255,0.85)';
    if (focus && !(focus[endId(l.source)] && focus[endId(l.target)])) return 'rgba(160,160,200,0.03)';
    return 'rgba(160,160,200,' + (0.12 + 0.5 * l.w / maxW).toFixed(2) + ')';
  }
  function linkWidth(l) { var wn = l.w / maxW; return linkOn(l) ? 1.6 : (wn > 0.5 ? 0.4 + wn : 0); }
  /** Связь рисуем, если оба конца показаны и она не слабее порога (связи выбранного узла — всегда). */
  function linkVis(l) {
    var a = endId(l.source), b = endId(l.target);
    if (!vis(byId[a]) || !vis(byId[b])) return false;
    return linkThr === 0 || l.w >= linkThr || a === sel || b === sel;
  }
  function nodeVis(n) { return vis(n); }

  var fitted = false;
  // слабый телефон: без сглаживания, ограниченное число шагов раскладки, пиксели не больше ×1.5
  var G = ForceGraph3D({ rendererConfig: { antialias: false, powerPreference: 'low-power' } })($('graph'))
    .backgroundColor('#0f0f1a')
    .showNavInfo(false)
    .enableNodeDrag(false)
    .nodeId('id')
    .nodeVal(nodeVal)
    .nodeRelSize(2.2)
    .nodeResolution(6)
    .nodeVisibility(nodeVis)
    .linkVisibility(linkVis)
    .nodeOpacity(1)
    .nodeColor(nodeColor)
    .nodeLabel(function () { return ''; })
    .linkOpacity(1)
    .linkResolution(2)
    .linkColor(linkColor)
    .linkWidth(linkWidth)
    .warmupTicks(nodes.length > 800 ? 20 : 60)
    .cooldownTicks(nodes.length > 800 ? 90 : 150)
    .cooldownTime(nodes.length > 800 ? 3500 : 5000)
    .d3AlphaDecay(0.04)
    .onEngineStop(function () { if (!fitted) { fitted = true; G.zoomToFit(600, 20); } })   // один раз: весь граф в кадре
    .onNodeClick(function (n) { select(n.id, true); })
    .onBackgroundClick(function () { clearAll(); })
    .graphData({ nodes: nodes.map(function (n) { return Object.assign({}, n); }), links: D.links.map(function (l) { return Object.assign({}, l); }) });
  try { G.renderer().setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5)); } catch (e) { /* без этого тоже работает */ }
  var link = G.d3Force('link'); if (link) link.distance(34).strength(function (l) { return 0.15 + 0.6 * l.w / maxW; });
  var charge = G.d3Force('charge'); if (charge) charge.strength(-26);
  // притяжение к центру: одиночные теги без связей иначе разлетаются, и граф в кадре выходит мелким
  G.d3Force('gravity', function (alpha) {
    G.graphData().nodes.forEach(function (n) { var k = 0.06 * alpha; n.vx -= n.x * k; n.vy -= n.y * k; n.vz -= n.z * k; });
  });
  setTimeout(function () { if (!fitted) G.zoomToFit(400, 20); }, 1500);   // первый вид — не дожидаясь конца раскладки
  var live = {};                              // узлы с координатами, которые считает движок
  G.graphData().nodes.forEach(function (n) { live[n.id] = n; });

  // ---- подписи: HTML поверх сцены, позиции из камеры (без текстур и объектов three.js) ----
  var shown = {}, boxes = {};
  function labelSet() {
    var s = {};
    var c = 0;
    if (comm >= 0) nodes.forEach(function (n) { if (n.c === comm && vis(n) && c++ < LABELS) s[n.id] = 1; });
    else nodes.forEach(function (n) { if (vis(n) && c++ < LABELS) s[n.id] = 1; });
    if (sel) { s[sel] = 1; nb[sel].forEach(function (x) { s[x.id] = 1; }); }
    if (found) Object.keys(found).slice(0, 20).forEach(function (k) { if (byId[k] && vis(byId[k])) s[k] = 1; });
    return s;
  }
  function relabel() {
    shown = labelSet();
    Object.keys(boxes).forEach(function (k) { if (!shown[k]) { boxes[k].remove(); delete boxes[k]; } });
    Object.keys(shown).forEach(function (k) {
      if (boxes[k]) return;
      var d = document.createElement('div'); d.className = 'lb'; d.textContent = labOf(byId[k]);
      d.style.fontSize = fontSize(k);
      $('labels').appendChild(d); boxes[k] = d;
    });
  }
  /** Приоритет подписи: выбранный и найденный — первыми, дальше тяжёлые; наезжающие на уже показанные — прячем. */
  function rank(k) { return (k === sel ? 1e9 : 0) + (found && found[k] ? 1e8 : 0) + (sel && focus && focus[k] ? 1e7 : 0) + byId[k].act; }
  function frame() {
    var cam = G.camera(), dir = cam.position.clone(), P = cam.position;
    cam.getWorldDirection(dir);
    var taken = [];
    Object.keys(boxes).sort(function (a, b) { return rank(b) - rank(a); }).forEach(function (k) {
      var n = live[k], b = boxes[k];
      if (!n || n.x === undefined) { b.style.display = 'none'; return; }
      if ((n.x - P.x) * dir.x + (n.y - P.y) * dir.y + (n.z - P.z) * dir.z <= 1) { b.style.display = 'none'; return; }   // позади камеры
      var p = G.graph2ScreenCoords(n.x, n.y, n.z);
      var fs = parseFloat(b.style.fontSize), w = b.textContent.length * fs * 0.56, h = fs * 1.3, x = p.x - w / 2, y = p.y + 8;
      if (taken.some(function (t) { return x < t[2] && x + w > t[0] && y < t[3] && y + h > t[1]; })) { b.style.display = 'none'; return; }
      taken.push([x, y, x + w, y + h]);
      b.style.display = '';
      b.style.transform = 'translate(' + p.x.toFixed(0) + 'px,' + (p.y + 8).toFixed(0) + 'px) translate(-50%,0)';
      b.style.opacity = focus && !focus[k] ? 0.25 : 1;
    });
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);
  document.addEventListener('visibilitychange', function () { if (document.hidden) G.pauseAnimation(); else G.resumeAnimation(); });

  // ---- состояние: выбор, сообщество, поиск ----
  function compute() {
    focus = null;
    if (sel) { focus = {}; focus[sel] = 1; nb[sel].forEach(function (x) { focus[x.id] = 1; }); }
    else if (comm >= 0) { focus = {}; nodes.forEach(function (n) { if (n.c === comm) focus[n.id] = 1; }); }
    else if (found) focus = found;
    G.nodeColor(nodeColor).linkColor(linkColor).linkWidth(linkWidth).linkVisibility(linkVis);
    relabel(); legendMarks();
  }
  function fly(id) {
    var n = live[id]; if (!n || n.x === undefined) return;
    var len = Math.hypot(n.x, n.y, n.z), d = 90, k = 1 + d / len;
    G.cameraPosition(len < 1 ? { x: n.x, y: n.y, z: n.z + d } : { x: n.x * k, y: n.y * k, z: n.z * k }, n, 800);
  }
  function part(box, label, val) {
    var s = document.createElement('div'); s.appendChild(document.createTextNode(label + ': '));
    var b = document.createElement('b'); b.textContent = val; s.appendChild(b); box.appendChild(s);
  }
  function select(id, noFly) {
    sel = id; comm = -1; found = null; $('q').value = '';
    var n = byId[id], c = comms[n.c], m = $('dm');
    $('dc').style.background = c.color; $('dn').textContent = labOf(n); m.textContent = '';
    var tw = document.createElement('div'); tw.textContent = T1[n.t] || n.t; m.appendChild(tw);
    part(m, T.own || '', n.own.toFixed(1)); part(m, T.net || '', n.act.toFixed(1)); part(m, T.community || '', lab(c.t, c.name));
    if (n.suggested && T.suggested) { var s = document.createElement('div'); s.textContent = T.suggested; m.appendChild(s); }
    weightUi(n);
    $('ds').textContent = T.neighbours || '';
    var dl = $('dl'); dl.textContent = '';
    if (!nb[id].length) dl.textContent = T.noLinks || '';
    nb[id].filter(function (x) { return vis(byId[x.id]); }).forEach(function (x) {
      var b = document.createElement('button'); b.type = 'button'; b.className = 'chip';
      var i = document.createElement('i'); i.style.background = comms[byId[x.id].c].color; b.appendChild(i);
      b.appendChild(document.createTextNode(labOf(byId[x.id])));
      var bar = document.createElement('span'); bar.style.width = (8 + 28 * x.w / maxW).toFixed(0) + 'px'; b.appendChild(bar);
      b.onclick = function () { select(x.id); };
      dl.appendChild(b);
    });
    $('detail').style.display = 'block'; $('detail').scrollTop = 0;
    $('stat').textContent = stats();
    compute();
    if (!noFly) fly(id);
  }
  // ---- ползунок «мой вес тега»: множитель к выученному весу; двигаешь — узел растёт сразу, отпустил — сохраняем ----
  $('wl').textContent = T.weight || ''; $('wh').textContent = T.weightHint || ''; $('wr').textContent = T.reset || '';
  $('w').min = T.min || 0.1; $('w').max = T.max || 3;
  function fmt(m) { return '×' + Number(m).toFixed(1); }
  function weightUi(n) { $('w').value = n.boost || 1; $('wv').textContent = fmt(n.boost || 1); }
  function preview(m) {
    var n = byId[sel], b = n.boost || 1;
    [n, live[sel]].forEach(function (x) { if (x) { x.act = x.act / b * m; x.own = x.own / b * m; x.boost = m; } });
    G.nodeVal(nodeVal); if (boxes[sel]) boxes[sel].style.fontSize = fontSize(sel);
    $('wv').textContent = fmt(m);
  }
  function commit(m) {
    var tag = sel, R;
    try { R = JSON.parse(Loopa.setBoost(tag, m)); } catch (e) { return; }
    // новые веса — в те же объекты (раскладка не сбрасывается); соседи тоже меняются: вес идёт по связям
    R.nodes.forEach(function (r) { [byId[r.id], live[r.id]].forEach(function (x) { if (x) { x.act = r.act; x.own = r.own; x.boost = r.boost; x.suggested = r.suggested; } }); });
    calcRef();
    G.nodeVal(nodeVal).nodeColor(nodeColor);
    Object.keys(boxes).forEach(function (k) { boxes[k].style.fontSize = fontSize(k); });
    if (sel === tag) select(tag, true);
  }
  $('w').addEventListener('input', function () { if (sel) preview(parseFloat(this.value)); });
  $('w').addEventListener('change', function () { if (sel) commit(parseFloat(this.value)); });
  $('wr').onclick = function () { if (sel) { $('w').value = 1; preview(1); commit(1); } };

  // ---- масштаб: кнопки + / − (двумя пальцами — тоже) и «весь граф» ----
  function zoom(k) {
    var c = G.camera().position, t = G.controls().target;
    G.cameraPosition({ x: t.x + (c.x - t.x) * k, y: t.y + (c.y - t.y) * k, z: t.z + (c.z - t.z) * k }, t, 300);
  }
  $('zi').onclick = function () { zoom(0.7); };
  $('zo').onclick = function () { zoom(1 / 0.7); };
  $('zf').onclick = function () { G.zoomToFit(500, 20); };
  $('zf').title = T.fit || '';

  function clearAll() { sel = null; comm = -1; found = null; $('detail').style.display = 'none'; $('q').value = ''; $('q').blur(); $('stat').textContent = stats(); compute(); }
  $('dx').onclick = clearAll;
  $('open').onclick = function () { if (sel) Loopa.open(sel); };
  // «Назад» в приложении: сначала снять выбор, потом закрыть экран
  window.backHandled = function () { if (sel || comm >= 0 || found) { clearAll(); return true; } return false; };

  // ---- легенда сообществ ----
  var chips = [];
  (function () {
    var list = comms.filter(function (c) { return c.size >= 2; });
    if (!list.length) list = comms.slice(0, 8);
    list.slice(0, 14).forEach(function (c) {
      var b = document.createElement('button'); b.type = 'button'; b.className = 'chip';
      var i = document.createElement('i'); i.style.background = c.color; b.appendChild(i);
      b.appendChild(document.createTextNode(lab(c.t, c.name)));
      var s = document.createElement('small'); s.textContent = c.size; b.appendChild(s);
      b.onclick = function () {
        var on = comm !== c.id; sel = null; found = null; $('detail').style.display = 'none'; $('q').value = '';
        comm = on ? c.id : -1; compute();
      };
      $('legend').appendChild(b); chips.push([c.id, b]);
    });
  })();
  function legendMarks() { chips.forEach(function (p) { p[1].classList.toggle('on', p[0] === comm); }); }

  // ---- поиск тега: подсветка сразу, подлёт камеры — когда договорил ----
  var timer = 0;
  function hits(q) { return nodes.filter(function (n) { return vis(n) && n.name.toLowerCase().indexOf(q) >= 0; }); }
  $('q').addEventListener('input', function () {
    var q = this.value.trim().toLowerCase();
    sel = null; comm = -1; $('detail').style.display = 'none';
    clearTimeout(timer);
    if (!q) { found = null; $('stat').textContent = stats(); compute(); return; }
    var hit = hits(q);
    found = {}; hit.forEach(function (n) { found[n.id] = 1; });
    if (!hit.length) found = { '\u0000': 1 };          // ничего не нашлось — всё приглушено
    $('stat').textContent = hit.length ? stats() : (T.notFound || '');
    compute();
    if (hit.length) timer = setTimeout(function () { fly(hit[0].id); }, 450);
  });
  $('q').addEventListener('keydown', function (e) {
    if (e.key !== 'Enter') return;
    var q = this.value.trim().toLowerCase(), hit = q ? hits(q)[0] : null;
    this.blur();
    if (hit) { clearTimeout(timer); select(hit.id); }
  });
  // ---- фильтр по типам: чипы-переключатели с числом узлов; выключенный тип скрыт вместе со своими связями ----
  var counts = {};
  nodes.forEach(function (n) { counts[n.t] = (counts[n.t] || 0) + 1; });
  TYPES.filter(function (t) { return counts[t]; }).forEach(function (t) {
    var b = document.createElement('button'); b.type = 'button'; b.className = 'chip on';
    b.appendChild(document.createTextNode((PREFIX[t] ? PREFIX[t] + ' ' : '') + (TT[t] || t)));
    var s = document.createElement('small'); s.textContent = counts[t]; b.appendChild(s);
    b.onclick = function () {
      typeOn[t] = !typeOn[t]; b.classList.toggle('on', typeOn[t]);
      if (sel && !vis(byId[sel])) { clearAll(); }
      G.nodeVisibility(nodeVis).linkVisibility(linkVis);
      $('stat').textContent = stats(); compute();
    };
    $('types').appendChild(b);
  });
  window.addEventListener('resize', function () { G.width(window.innerWidth).height(window.innerHeight); });
  relabel();
})();
