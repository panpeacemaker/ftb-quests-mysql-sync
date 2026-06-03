'use strict';

const API = {
  STATUS:            '/api/agrarius/status',
  QUESTBOOK:         '/api/agrarius/questbook',
  QUESTBOOK_REFRESH: '/api/agrarius/questbook/refresh',
  TARGETS:           '/api/agrarius/targets',
  RESET:             '/api/agrarius/reset',
  AUDIT:             '/api/agrarius/audit',
};

const state = {
  questbook: null,
  targets: { players: [], teams: [] },
  target: null,
  scopeType: 'PLAYER',
  chapterId: null,
  questId: null,
  includeRewards: true,
  includeRanks: true,
  immediate: true,
  lang: { chapterKeys: {}, groupKeys: {}, groupById: {} },
  langReady: false,
  progress: { completed: new Set(), started: new Set() },
  textureManifest: null,
};

function $id(id) { return document.getElementById(id); }
function apiFetch(url, opts) { return fetch(url, Object.assign({ credentials: 'include' }, opts || {})); }
function clearChildren(el) { while (el.firstChild) el.removeChild(el.firstChild); }
function debounce(fn, delay) { let t = null; return function () { const args = arguments; clearTimeout(t); t = setTimeout(() => fn.apply(this, args), delay); }; }

function swalTheme() {
  return { background: '#161b22', color: '#e6edf3', customClass: { popup: 'swal-dark-popup' } };
}

function formatTs(ts) {
  if (!ts) return '—';
  const d = new Date(typeof ts === 'number' ? ts : ts);
  if (Number.isNaN(d.getTime())) return String(ts);
  return d.toLocaleString('cs-CZ', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

async function init() {
  try {
    const res = await apiFetch(API.STATUS);
    if (res.status === 401) { showAuthWall(); return; }
    if (!res.ok) throw new Error('HTTP ' + res.status);
    showApp(await res.json());
    await Promise.all([loadQuestbook(false), loadTargets(''), loadAudit(), loadLang(), loadTextureManifest()]);
  } catch (err) {
    showAuthWall();
  }
}

function showAuthWall() {
  $id('auth-wall').hidden = false;
  const form = $id('login-form');
  if (form && !form.dataset.wired) {
    form.dataset.wired = '1';
    form.addEventListener('submit', onLoginSubmit);
  }
}

function setLoginMessage(message) {
  const errBox = $id('login-error');
  errBox.textContent = message || '';
  errBox.hidden = !message;
}

function setLoginBusy(isBusy) {
  const btn = $id('login-btn');
  btn.disabled = isBusy;
  btn.innerHTML = isBusy ? '<i class="fa-solid fa-spinner fa-spin"></i>&nbsp;Přihlašuji…' : '<i class="fa-solid fa-right-to-bracket"></i>&nbsp;Přihlásit se';
}

async function onLoginSubmit(e) {
  e.preventDefault();
  const username = $id('login-user').value.trim();
  const password = $id('login-pass').value;
  setLoginMessage('');
  if (!username || !password) { setLoginMessage('Vyplň jméno i heslo.'); return; }
  setLoginBusy(true);
  try {
    const res = await fetch('/api/agrarius/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, credentials: 'include',
      body: JSON.stringify({ username, password }),
    });
    if (!res.ok) {
      let msg = 'Špatné jméno nebo heslo';
      try { const d = await res.json(); if (d && d.error) msg = d.error; } catch (_) {}
      setLoginMessage(msg); return;
    }
    window.location.href = '/agrarius/?login=ok&t=' + Date.now();
  } catch (err) {
    setLoginMessage('Přihlášení selhalo: ' + err.message);
  } finally {
    setLoginBusy(false);
  }
}

function showApp(status) {
  $id('auth-wall').hidden = true;
  $id('app').hidden = false;
  if (status.env) {
    const badge = $id('env-badge');
    badge.textContent = status.env.toUpperCase();
    badge.className = 'badge badge-' + (status.env.toLowerCase() === 'test' ? 'green' : 'blue');
    badge.style.display = '';
  }
  setDot('dot-db', !!status.db);
  setDot('dot-redis', !!status.redis);
  $id('user-name').textContent = status.user || status.username || status.name || '';
}

function setDot(dotId, ok) {
  const dot = $id(dotId).querySelector('.dot');
  dot.classList.toggle('dot-ok', ok);
  dot.classList.toggle('dot-bad', !ok);
}

async function loadTextureManifest() {
  try {
    const res = await apiFetch('/api/agrarius/textures/manifest');
    if (!res.ok) return;
    const data = await res.json();
    state.textureManifest = new Set((data.available || []).map((s) => String(s).toLowerCase()));
  } catch (e) { state.textureManifest = null; }
}

async function loadProgress() {
  if (!state.target) { state.progress = { completed: new Set(), started: new Set() }; return; }
  try {
    const res = await apiFetch(`/api/agrarius/progress?scope=${state.target.type}&targetId=${encodeURIComponent(state.target.id)}`);
    if (!res.ok) { state.progress = { completed: new Set(), started: new Set() }; return; }
    const data = await res.json();
    state.progress = {
      completed: new Set((data.completed || []).map((s) => String(s).toUpperCase())),
      started: new Set((data.started || []).map((s) => String(s).toUpperCase())),
    };
  } catch (e) { state.progress = { completed: new Set(), started: new Set() }; }
}

function questState(quest, chapterQuests) {
  const id = String(quest.id || '').toUpperCase();
  if (state.progress.completed.has(id)) return 'completed';
  if (state.progress.started.has(id)) return 'started';
  const deps = (quest.dependencies || []).map((d) => String(d).toUpperCase());
  if (deps.length && chapterQuests) {
    const allDone = deps.every((d) => state.progress.completed.has(d) || chapterQuests.find((q) => String(q.id).toUpperCase() === d));
    if (!allDone) return 'locked';
  }
  return 'available';
}

function initDragPan() {
  const wrap = $id('quest-map-wrap');
  const map = $id('quest-map');
  if (!wrap || !map || wrap.dataset.panWired) return;
  wrap.dataset.panWired = '1';
  let startX = 0, startY = 0, scrollL = 0, scrollT = 0, dragging = false;
  let scale = 1;
  const minScale = 0.5;
  const maxScale = 1.6;

  wrap.addEventListener('mousedown', (e) => {
    if (e.target.closest('.quest-node')) return;
    if (e.button !== 0) return;
    dragging = true;
    startX = e.pageX; startY = e.pageY;
    scrollL = wrap.scrollLeft; scrollT = wrap.scrollTop;
    wrap.classList.add('dragging');
    e.preventDefault();
  });
  document.addEventListener('mousemove', (e) => {
    if (!dragging) return;
    wrap.scrollLeft = scrollL - (e.pageX - startX);
    wrap.scrollTop = scrollT - (e.pageY - startY);
  });
  document.addEventListener('mouseup', () => {
    if (!dragging) return;
    dragging = false;
    wrap.classList.remove('dragging');
  });
  wrap.addEventListener('wheel', (e) => {
    if (!e.ctrlKey) return;
    e.preventDefault();
    const delta = -e.deltaY;
    const newScale = Math.min(maxScale, Math.max(minScale, scale + (delta > 0 ? 0.1 : -0.1)));
    if (newScale === scale) return;
    const rect = wrap.getBoundingClientRect();
    const cx = e.clientX - rect.left + wrap.scrollLeft;
    const cy = e.clientY - rect.top + wrap.scrollTop;
    scale = newScale;
    map.style.transformOrigin = '0 0';
    map.style.transform = `scale(${scale})`;
    const lines = $id('quest-lines');
    if (lines) { lines.style.transformOrigin = '0 0'; lines.style.transform = `scale(${scale})`; }
    wrap.scrollLeft = cx * scale - (e.clientX - rect.left);
    wrap.scrollTop = cy * scale - (e.clientY - rect.top);
  }, { passive: false });
}

async function loadLang() {
  try {
    const res = await apiFetch('/api/agrarius/lang');
    if (!res.ok) return;
    const data = await res.json();
    state.lang = {
      chapterKeys: data.chapterKeys || {},
      groupKeys: data.groupKeys || {},
      groupById: data.groupById || {},
    };
    state.langReady = true;
  } catch (e) { state.langReady = true; }
}

async function loadQuestbook(forceRefresh) {
  const btn = $id('btn-refresh-questbook');
  if (btn) btn.disabled = true;
  try {
    const res = await apiFetch(forceRefresh ? API.QUESTBOOK_REFRESH : API.QUESTBOOK, { method: forceRefresh ? 'POST' : 'GET' });
    if (!res.ok) throw new Error('HTTP ' + res.status);
    state.questbook = await res.json();
    $id('questbook-loaded').textContent = 'Questbook: ' + (state.questbook.counts ? `${state.questbook.counts.chapters} kapitol / ${state.questbook.counts.quests} questů` : formatTs(state.questbook.loadedAt));
    renderChapterTree();
    const chapters = state.questbook.chapters || [];
    const selected = chapters.some((chapter) => chapter.id === state.chapterId);
    if ((!state.chapterId || !selected) && chapters.length) selectChapter(chapters[0].id);
    else if (state.target) { renderQuestMap(); renderQuestDetail(currentQuest()); }
  } catch (err) {
    Swal.fire(Object.assign({ icon: 'error', title: 'Questbook', text: 'Chyba načítání questbooku: ' + err.message }, swalTheme()));
  } finally {
    if (btn) btn.disabled = false;
  }
}

async function loadTargets(query) {
  const count = $id('target-count');
  count.textContent = 'Načítám…';
  try {
    const res = await apiFetch(API.TARGETS + '?q=' + encodeURIComponent(query || ''));
    if (!res.ok) throw new Error('HTTP ' + res.status);
    const data = await res.json();
    state.targets.players = data.players || [];
    state.targets.teams = data.teams || [];
    renderTargetList();
  } catch (err) {
    count.textContent = 'Chyba: ' + err.message;
  }
}

function renderTargetList() {
  const list = $id('target-list');
  const count = $id('target-count');
  clearChildren(list);
  const isPlayer = state.scopeType === 'PLAYER';
  const items = isPlayer ? state.targets.players : state.targets.teams;
  count.textContent = `${items.length} ${isPlayer ? 'hráčů' : 'týmů'}`;
  if (!items.length) {
    const empty = document.createElement('div');
    empty.className = 'target-empty';
    empty.textContent = isPlayer ? 'Žádní hráči' : 'Žádné týmy';
    list.appendChild(empty);
    return;
  }
  items.forEach((item) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'target-row';
    row.innerHTML = `<i class="fa-solid ${isPlayer ? 'fa-user' : 'fa-users'}"></i><span class="target-name"></span><span class="target-meta"></span>`;
    row.querySelector('.target-name').textContent = item.name;
    row.querySelector('.target-meta').textContent = isPlayer
      ? (item.teamId ? 'team ' + String(item.teamId).slice(0, 8) + '…' : 'solo')
      : 'owner ' + (item.owner ? String(item.owner).slice(0, 8) + '…' : '?');
    row.addEventListener('click', () => selectTarget(item));
    list.appendChild(row);
  });
}

function selectTarget(item) {
  const isPlayer = state.scopeType === 'PLAYER';
  state.target = { id: isPlayer ? item.uuid : item.teamId, name: item.name, type: state.scopeType, inTeam: isPlayer && !!item.teamId && item.teamId !== item.uuid };
  $id('selected-target-card').hidden = false;
  $id('selected-target-name').textContent = item.name;
  $id('selected-target-meta').textContent = isPlayer ? 'UUID: ' + item.uuid : 'Team ID: ' + item.teamId;
  $id('target-warning').hidden = !state.target.inTeam;
  $id('empty-workspace').hidden = true;
  $id('questbook-workspace').hidden = false;
  $id('workspace-target-title').textContent = `${item.name} · ${isPlayer ? 'solo hráč' : 'tým'}`;
  renderChapterTree();
  if (state.chapterId) renderQuestMap();
  loadProgress().then(() => { if (state.chapterId) renderQuestMap(); });
}

function clearTargetSelection() {
  state.target = null;
  state.questId = null;
  state.progress = { completed: new Set(), started: new Set() };
  $id('selected-target-card').hidden = true;
  $id('empty-workspace').hidden = false;
  $id('questbook-workspace').hidden = true;
}

function groupChapters() {
  const chapters = (state.questbook && state.questbook.chapters) || [];
  const standalone = [];
  const grouped = new Map();
  chapters.forEach((chapter) => {
    if (!chapter.groupId) { standalone.push(chapter); return; }
    if (!grouped.has(chapter.groupId)) grouped.set(chapter.groupId, { id: chapter.groupId, title: chapter.group || chapter.groupId, chapters: [] });
    grouped.get(chapter.groupId).chapters.push(chapter);
  });
  return { standalone, grouped: Array.from(grouped.values()) };
}

function renderChapterTree() {
  const tree = $id('chapter-tree');
  if (!tree || !state.questbook) return;
  clearChildren(tree);
  const root = document.createElement('div');
  root.className = 'chapter-root';
  root.innerHTML = '<i class="fa-solid fa-cube"></i><span>Agrarius</span>';
  tree.appendChild(root);
  const data = groupChapters();
  data.standalone.forEach((chapter) => tree.appendChild(chapterButton(chapter)));
  data.grouped.forEach((group) => {
    const block = document.createElement('div');
    block.className = 'chapter-group';
    const title = document.createElement('div');
    title.className = 'chapter-group-title';
    title.innerHTML = '<i class="fa-solid fa-caret-down"></i><span></span>';
    title.querySelector('span').textContent = displayGroupTitle(group);
    block.appendChild(title);
    group.chapters.forEach((chapter) => block.appendChild(chapterButton(chapter)));
    tree.appendChild(block);
  });
}

function chapterButton(chapter) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'chapter-button' + (chapter.id === state.chapterId ? ' active' : '');
  btn.innerHTML = '<span class="mc-icon"></span><span class="chapter-title"></span>';
  const iconSlot = btn.querySelector('.mc-icon');
  clearChildren(iconSlot);
  iconSlot.appendChild(makeItemImg(chapter.icon, chapter.title || chapter.id));
  btn.querySelector('.chapter-title').textContent = displayTitle(chapter.title || chapter.filename || chapter.id);
  btn.addEventListener('click', () => selectChapter(chapter.id));
  return btn;
}

function selectChapter(chapterId) {
  state.chapterId = chapterId;
  state.questId = null;
  renderChapterTree();
  renderQuestMap();
  renderQuestDetail(null);
  const chapter = currentChapter();
  $id('active-chapter-label').textContent = chapter ? ' · ' + displayTitle(chapter.title) : '';
  $id('btn-reset-chapter').disabled = !chapter;
}

function currentChapter() {
  const chapters = (state.questbook && state.questbook.chapters) || [];
  return chapters.find((chapter) => chapter.id === state.chapterId) || null;
}

function renderQuestMap() {
  const map = $id('quest-map');
  const lines = $id('quest-lines');
  clearChildren(map);
  clearChildren(lines);
  const chapter = currentChapter();
  if (!chapter) return;
  const quests = chapter.quests || [];
  const bounds = questBounds(quests);
  const scale = 70;
  const pad = 140;
  const width = Math.max(900, Math.round((bounds.maxX - bounds.minX) * scale + pad * 2));
  const height = Math.max(560, Math.round((bounds.maxY - bounds.minY) * scale + pad * 2));
  map.style.width = width + 'px';
  map.style.height = height + 'px';
  lines.setAttribute('viewBox', `0 0 ${width} ${height}`);
  lines.setAttribute('width', width);
  lines.setAttribute('height', height);
  const positions = new Map();
  quests.forEach((quest) => positions.set(quest.id, questPoint(quest, bounds, scale, pad)));
  quests.forEach((quest) => {
    const to = positions.get(quest.id);
    (quest.dependencies || []).forEach((depId) => {
      const from = positions.get(depId);
      if (!from || !to) return;
      const line = document.createElementNS('http://www.w3.org/2000/svg', 'line');
      line.setAttribute('x1', from.x); line.setAttribute('y1', from.y); line.setAttribute('x2', to.x); line.setAttribute('y2', to.y);
      line.setAttribute('class', 'quest-link');
      lines.appendChild(line);
    });
  });
  quests.forEach((quest) => {
    const point = positions.get(quest.id);
    const st = questState(quest, quests);
    const node = document.createElement('button');
    node.type = 'button';
    node.className = 'quest-node shape-' + safeClass(quest.shape || chapter.defaultQuestShape || 'hexagon')
      + (quest.id === state.questId ? ' selected' : '')
      + ' quest-' + st;
    node.style.left = point.x + 'px';
    node.style.top = point.y + 'px';
    node.title = displayTitle(quest.title) + ' · ' + quest.id + ' [' + st + ']';
    node.dataset.questId = quest.id;
    node.innerHTML = '<span class="quest-icon"></span>' + (st === 'completed' ? '<span class="quest-check">✓</span>' : '');
    const iconSlot = node.querySelector('.quest-icon');
    clearChildren(iconSlot);
    iconSlot.appendChild(makeItemImg(quest.icon, quest.title || quest.id));
    node.addEventListener('click', () => selectQuest(quest.id));
    map.appendChild(node);
  });
}

function questBounds(quests) {
  const xs = quests.map((q) => q.x || 0);
  const ys = quests.map((q) => q.y || 0);
  return { minX: Math.min(...xs, 0), maxX: Math.max(...xs, 0), minY: Math.min(...ys, 0), maxY: Math.max(...ys, 0) };
}

function questPoint(quest, bounds, scale, pad) {
  return { x: Math.round(((quest.x || 0) - bounds.minX) * scale + pad), y: Math.round(((quest.y || 0) - bounds.minY) * scale + pad) };
}

function selectQuest(questId) {
  state.questId = questId;
  renderQuestMap();
  renderQuestDetail(currentQuest());
  scrollToQuestNode(questId);
}

function scrollToQuestNode(questId) {
  const escaped = String(questId || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  const node = $id('quest-map').querySelector(`[data-quest-id="${escaped}"]`);
  if (node) node.scrollIntoView({ behavior: 'smooth', block: 'center', inline: 'center' });
}

function currentQuest() {
  const chapter = currentChapter();
  return chapter ? (chapter.quests || []).find((quest) => quest.id === state.questId) || null : null;
}

function renderQuestDetail(quest) {
  const detail = $id('quest-detail');
  clearChildren(detail);
  if (!quest) {
    const empty = document.createElement('div');
    empty.className = 'detail-empty';
    empty.textContent = 'Klikni na quest v mapě.';
    detail.appendChild(empty);
    return;
  }
  const chapter = currentChapter();
  const header = document.createElement('div');
  header.className = 'detail-header';
  header.innerHTML = '<div class="detail-icon"></div><div><h3></h3><p></p></div>';
  const detailIcon = header.querySelector('.detail-icon');
  clearChildren(detailIcon);
  detailIcon.appendChild(makeItemImg(quest.icon, quest.title || quest.id));
  header.querySelector('h3').textContent = displayTitle(quest.title);
  header.querySelector('p').textContent = `${displayTitle(chapter.title)} · ${quest.id}`;
  detail.appendChild(header);
  if (quest.description && quest.description.length) detail.appendChild(detailText(quest.description.map(displayTitle).join(' ')));
  detail.appendChild(detailList('Tasky', quest.tasks || []));
  detail.appendChild(detailList('Odměny', quest.rewards || []));
  const actions = document.createElement('div');
  actions.className = 'detail-actions';
  actions.innerHTML = '<button class="btn btn-danger" data-kind="quest-all"><i class="fa-solid fa-bolt"></i> Reset quest + odměny</button><button class="btn" data-kind="quest-progress">Jen progress</button><button class="btn" data-kind="quest-rewards">Jen odměny</button>';
  actions.querySelector('[data-kind="quest-all"]').addEventListener('click', () => resetScope('QUEST', { includeRewards: true, resetKind: 'ALL' }));
  actions.querySelector('[data-kind="quest-progress"]').addEventListener('click', () => resetScope('QUEST', { includeRewards: false, resetKind: 'PROGRESS_ONLY' }));
  actions.querySelector('[data-kind="quest-rewards"]').addEventListener('click', () => resetScope('QUEST', { includeRewards: true, includeRanks: false, resetKind: 'REWARDS_ONLY' }));
  detail.appendChild(actions);
}

function detailText(text) {
  const el = document.createElement('p');
  el.className = 'detail-text';
  el.textContent = text;
  return el;
}

function detailList(title, items) {
  const wrap = document.createElement('div');
  wrap.className = 'detail-list';
  const h = document.createElement('h4');
  h.textContent = `${title} (${items.length})`;
  wrap.appendChild(h);
  if (!items.length) {
    const empty = document.createElement('div');
    empty.className = 'detail-small';
    empty.textContent = 'Žádné položky';
    wrap.appendChild(empty);
    return wrap;
  }
  items.forEach((item) => {
    const row = document.createElement('div');
    row.className = 'detail-row';
    const label = item.title || (item.item && item.item.label) || item.type || item.id;
    row.innerHTML = '<span class="detail-row-icon"></span><span class="detail-row-main"></span><span class="detail-row-count"></span>';
    const rowIcon = row.querySelector('.detail-row-icon');
    clearChildren(rowIcon);
    const rowItemId = item.item && item.item.id;
    if (rowItemId) {
      rowIcon.appendChild(makeItemImg(rowItemId, item.title || item.id));
    } else {
      rowIcon.textContent = '✓';
    }
    row.querySelector('.detail-row-main').textContent = displayTitle(label) + ' · ' + item.id;
    row.querySelector('.detail-row-count').textContent = item.count ? '×' + item.count : '';
    wrap.appendChild(row);
  });
  return wrap;
}

async function resetScope(mode, options) {
  if (!state.target) return;
  const chapter = currentChapter();
  const quest = currentQuest();
  if ((mode === 'CHAPTER' || mode === 'QUEST') && !chapter) return;
  if (mode === 'QUEST' && !quest) return;
  const includeRewards = Object.prototype.hasOwnProperty.call(options || {}, 'includeRewards') ? options.includeRewards : state.includeRewards;
  const includeRanks = Object.prototype.hasOwnProperty.call(options || {}, 'includeRanks') ? options.includeRanks : state.includeRanks;
  const resetKind = (options && options.resetKind) || 'ALL';
  const label = mode === 'FULL' ? 'Úplně vše' : mode === 'CHAPTER' ? 'Celá kapitola: ' + displayTitle(chapter.title) : 'Quest: ' + displayTitle(quest.title);
  const result = await Swal.fire(Object.assign({
    title: 'Potvrdit reset',
    html: buildConfirmHtml(label, includeRewards, includeRanks, resetKind),
    icon: 'warning', showCancelButton: true, confirmButtonText: 'Ano, resetovat', cancelButtonText: 'Zrušit', confirmButtonColor: '#f85149', focusCancel: true,
  }, swalTheme()));
  if (!result.isConfirmed) return;
  const body = {
    scope: state.target.type,
    targetId: state.target.id,
    mode,
    includeRewards,
    includeRanks,
    immediate: state.immediate,
    resetKind,
  };
  if (mode === 'CHAPTER' || mode === 'QUEST') body.chapterId = chapter.id;
  if (mode === 'QUEST') body.questId = quest.id;
  try {
    const res = await apiFetch(API.RESET, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const data = await res.json();
    if (!res.ok || data.ok === false) throw new Error(data.error || 'HTTP ' + res.status);
    await Swal.fire(Object.assign({ icon: 'success', title: 'Reset proveden', timer: 3500, timerProgressBar: true, showConfirmButton: false }, swalTheme()));
    loadAudit();
  } catch (err) {
    Swal.fire(Object.assign({ icon: 'error', title: 'Chyba resetu', text: err.message }, swalTheme()));
  }
}

function buildConfirmHtml(label, includeRewards, includeRanks, resetKind) {
  const target = `${state.target.name} (${state.target.type === 'PLAYER' ? 'hráč' : 'tým'})`;
  return `<table class="confirm-table"><tr><td>Cíl</td><td>${escapeHtml(target)}</td></tr><tr><td>Rozsah</td><td>${escapeHtml(label)}</td></tr><tr><td>Typ</td><td>${escapeHtml(resetKind)}</td></tr><tr><td>Odměny</td><td>${includeRewards ? 'ano' : 'ne'}</td></tr><tr><td>Ranky</td><td>${includeRanks ? 'ano' : 'ne'}</td></tr></table>`;
}

function searchQuestbook(query) {
  const out = [];
  const q = String(query || '').trim().toLowerCase();
  if (!q || !state.questbook) return out;
  (state.questbook.chapters || []).forEach((chapter) => {
    if (displayTitle(chapter.title).toLowerCase().includes(q) || chapter.id.toLowerCase().includes(q)) out.push({ type: 'chapter', chapter });
    (chapter.quests || []).forEach((quest) => {
      if (displayTitle(quest.title).toLowerCase().includes(q) || quest.id.toLowerCase().includes(q)) out.push({ type: 'quest', chapter, quest });
    });
  });
  return out.slice(0, 30);
}

function renderQuestSearch(query) {
  const box = $id('quest-search-results');
  clearChildren(box);
  const results = searchQuestbook(query);
  box.hidden = !query;
  if (!query) return;
  if (!results.length) {
    const empty = document.createElement('div'); empty.className = 'quest-search-empty'; empty.textContent = 'Nic nenalezeno'; box.appendChild(empty); return;
  }
  results.forEach((result) => {
    const row = document.createElement('button');
    row.type = 'button';
    row.className = 'quest-search-item';
    row.textContent = result.type === 'chapter' ? 'Kapitola · ' + displayTitle(result.chapter.title) : 'Quest · ' + displayTitle(result.quest.title) + ' · ' + displayTitle(result.chapter.title);
    row.addEventListener('click', () => {
      selectChapter(result.chapter.id);
      if (result.quest) selectQuest(result.quest.id);
      box.hidden = true;
      $id('quest-search').value = '';
    });
    box.appendChild(row);
  });
}

async function loadAudit() {
  const wrap = $id('audit-wrap');
  clearChildren(wrap);
  const loader = document.createElement('div');
  loader.className = 'loader';
  loader.textContent = 'Načítám audit…';
  wrap.appendChild(loader);
  try {
    const res = await apiFetch(API.AUDIT);
    if (!res.ok) throw new Error('HTTP ' + res.status);
    renderAudit(wrap, await res.json());
  } catch (err) {
    clearChildren(wrap);
    const el = document.createElement('div');
    el.className = 'error';
    el.textContent = 'Nepodařilo se načíst audit: ' + err.message;
    wrap.appendChild(el);
  }
}

function renderAudit(wrap, rows) {
  clearChildren(wrap);
  if (!Array.isArray(rows) || !rows.length) { const e = document.createElement('div'); e.className = 'muted'; e.textContent = 'Žádné záznamy.'; wrap.appendChild(e); return; }
  const table = document.createElement('table');
  table.className = 'table';
  table.innerHTML = '<thead><tr><th>Čas</th><th>Actor</th><th>Scope</th><th>Cíl</th><th>Mód</th><th>Detail</th></tr></thead><tbody></tbody>';
  const tbody = table.querySelector('tbody');
  rows.forEach((row) => {
    const tr = document.createElement('tr');
    [formatTs(row.ts), row.actor || '—', row.scope || '—', row.target_id || '—', row.mode || '—', auditDetail(row)].forEach((value) => {
      const td = document.createElement('td'); td.textContent = value; tr.appendChild(td);
    });
    tbody.appendChild(tr);
  });
  const scroll = document.createElement('div'); scroll.className = 'table-scroll'; scroll.appendChild(table); wrap.appendChild(scroll);
}

function auditDetail(row) {
  const parts = [];
  if (row.chapter_id) parts.push('kap: ' + row.chapter_id);
  if (row.quest_id) parts.push('quest: ' + row.quest_id);
  if (row.include_rewards) parts.push('+odměny');
  if (row.include_ranks) parts.push('+ranky');
  if (row.immediate) parts.push('imm');
  return parts.join(', ') || '—';
}

function displayTitle(value) {
  if (!value) return '';
  const v = String(value);
  const ck = state.lang && state.lang.chapterKeys;
  if (ck && ck[v]) return ck[v];
  const m = v.match(/^\{?(ftbquests\.chapter\.[^}]+)\.title\}?$/);
  if (m && ck && ck[m[1] + '.title']) return ck[m[1] + '.title'];
  if (m) return ck[m[1] + '.title'] || (m[1].split('.').pop().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()));
  const fname = v.split('.').pop().replace(/_/g, ' ');
  if (ck && ck['ftbquests.chapter.' + fname.replace(/\s+/g, '_') + '.title']) return ck['ftbquests.chapter.' + fname.replace(/\s+/g, '_') + '.title'];
  return fname.replace(/\b\w/g, (c) => c.toUpperCase());
}

function displayGroupTitle(groupOrTitle, groupId) {
  if (!groupOrTitle && !groupId) return '';
  const gk = state.lang && state.lang.groupKeys;
  const gbi = state.lang && state.lang.groupById;
  if (groupId && gbi && gbi[groupId]) return gbi[groupId];
  if (typeof groupOrTitle === 'object' && groupOrTitle) {
    if (groupOrTitle.id && gbi && gbi[groupOrTitle.id]) return gbi[groupOrTitle.id];
    const t = groupOrTitle.title || '';
    const m = String(t).match(/^\{?(ftbquests\.chapter_groups_[^}]+)\.title\}?$/);
    if (m && gk && gk[m[1] + '.title']) return gk[m[1] + '.title'];
    if (t) return displayTitle(t);
  }
  const t = String(groupOrTitle || '');
  const m = t.match(/^\{?(ftbquests\.chapter_groups_[^}]+)\.title\}?$/);
  if (m && gk && gk[m[1] + '.title']) return gk[m[1] + '.title'];
  return t;
}

function textureBaseId(itemId) {
  if (!itemId || typeof itemId !== 'string') return '';
  const parts = itemId.split(':');
  if (parts.length < 2) return '';
  return parts.slice(0, 2).join(':');
}

function textureUrl(itemId) {
  const base = textureBaseId(itemId);
  if (!base) return '';
  const [mod, name] = base.split(':');
  return `/agrarius/textures/${encodeURIComponent(mod)}/${encodeURIComponent(name)}`;
}

function glyphSpan(itemId) {
  const span = document.createElement('span');
  span.className = 'item-glyph';
  span.textContent = fallbackGlyph(itemId);
  return span;
}

function makeItemImg(itemId, alt) {
  const base = textureBaseId(itemId).toLowerCase();
  const url = textureUrl(itemId);
  if (!url) return glyphSpan(itemId);
  if (state.textureManifest && !state.textureManifest.has(base)) return glyphSpan(itemId);
  const img = document.createElement('img');
  img.className = 'item-img';
  img.src = url;
  img.alt = alt || itemId;
  img.loading = 'lazy';
  img.onerror = function onErr() {
    this.onerror = null;
    this.replaceWith(Object.assign(document.createElement('span'), { className: 'item-glyph', textContent: fallbackGlyph(itemId) }));
  };
  return img;
}

function fallbackGlyph(itemId) {
  const text = String(itemId || '').toLowerCase();
  if (text.includes('rank')) return '✦';
  if (text.includes('power') || text.includes('energy') || text.includes('lv_') || text.includes('mv_') || text.includes('hv_')) return '⚡';
  if (text.includes('bevel') || text.includes('coin')) return '◈';
  if (text.includes('book')) return '▣';
  if (text.includes('water') || text.includes('fluid')) return '◌';
  if (text.includes('log') || text.includes('wood') || text.includes('plank')) return '▥';
  if (text.includes('stone') || text.includes('cobble') || text.includes('rock') || text.includes('gravel') || text.includes('sand')) return '◩';
  if (text.includes('coal') || text.includes('charcoal') || text.includes('fire') || text.includes('lava') || text.includes('furnace')) return '🜂';
  if (text.includes('iron') || text.includes('steel') || text.includes('ingot') || text.includes('plate')) return '◇';
  if (text.includes('diamond') || text.includes('emerald') || text.includes('crystal') || text.includes('gem')) return '◆';
  if (text.includes('glass')) return '◇';
  if (text.includes('circuit') || text.includes('wire') || text.includes('cable') || text.includes('resistor')) return '⚙';
  if (text.includes('machine') || text.includes('hull') || text.includes('casing')) return '⚙';
  return '⬢';
}

function safeClass(value) { return String(value || 'hexagon').toLowerCase().replace(/[^a-z0-9_-]/g, '-'); }
function escapeHtml(value) { return String(value).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c])); }

function wire() {
  initDragPan();
  document.querySelectorAll('input[name="scope-type"]').forEach((radio) => {
    radio.addEventListener('change', (e) => { state.scopeType = e.target.value; clearTargetSelection(); renderTargetList(); loadTargets($id('target-search').value.trim()); });
  });
  $id('target-search').addEventListener('input', debounce((e) => loadTargets(e.target.value.trim()), 220));
  $id('btn-clear-target').addEventListener('click', clearTargetSelection);
  $id('quest-search').addEventListener('input', debounce((e) => renderQuestSearch(e.target.value), 160));
  $id('cb-rewards').addEventListener('change', (e) => { state.includeRewards = e.target.checked; });
  $id('cb-ranks').addEventListener('change', (e) => { state.includeRanks = e.target.checked; });
  $id('cb-immediate').addEventListener('change', (e) => { state.immediate = e.target.checked; });
  $id('btn-refresh-questbook').addEventListener('click', () => loadQuestbook(true));
  $id('btn-reset-chapter').addEventListener('click', () => resetScope('CHAPTER', { resetKind: 'CHAPTER_ALL' }));
  $id('btn-reset-full').addEventListener('click', () => resetScope('FULL', { resetKind: 'FULL' }));
  $id('btn-refresh-audit').addEventListener('click', loadAudit);
}

wire();
init();
