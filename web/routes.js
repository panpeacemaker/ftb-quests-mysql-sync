'use strict';
const express = require('express');
const path = require('path');
const fs = require('fs');
const axios = require('axios');
const db = require('./db');
const blob = require('./nbt');
const questbook = require('./questbook');

const router = express.Router();
const WOT_LOGIN = process.env.WOT_LOGIN_URL || 'http://127.0.0.1:3005/WOT-zalohy/api/login';
const WOT_ME = process.env.WOT_ME_URL || 'http://127.0.0.1:3005/WOT-zalohy/api/me';
const AGRARIUS_ROLES = new Set(String(process.env.AGRARIUS_ROLES || 'admin').split(',').map((s) => s.trim()).filter(Boolean));
const AGRARIUS_ADMINS = new Set(String(process.env.AGRARIUS_ADMINS || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean));

function canUseAgrarius(user) {
  const username = String((user && user.username) || '').toLowerCase();
  const role = String((user && user.role) || '');
  return AGRARIUS_ROLES.has(role) || (username && AGRARIUS_ADMINS.has(username));
}

function normalizeWotCookie(cookie) {
  return String(cookie || '')
    .replace(/;\s*Path=[^;]*/i, '')
    .replace(/;\s*SameSite=[^;]*/i, '')
    .replace(/;\s*Secure\b/i, '') + '; Path=/; SameSite=Strict; Secure';
}

async function requireWotAgrarius(req, res) {
  try {
    const r = await axios.get(WOT_ME, { headers: { cookie: req.headers.cookie || '' }, timeout: 5000, validateStatus: () => true });
    if (!r.data || !r.data.loggedIn) { res.status(401).json({ error: 'Login required' }); return null; }
    if (!canUseAgrarius(r.data)) { res.status(403).json({ error: 'Agrarius admin access required' }); return null; }
    return { username: r.data.username, role: r.data.role };
  } catch (e) {
    res.status(503).json({ error: 'Auth check failed' });
    return null;
  }
}

router.post('/api/agrarius/login', async (req, res) => {
  try {
    const r = await axios.post(WOT_LOGIN, req.body || {}, {
      headers: { 'content-type': 'application/json' },
      timeout: 5000,
      validateStatus: () => true,
      maxRedirects: 0,
    });
    if (r.status >= 400) return res.status(r.status).json({ error: (r.data || {}).error || 'Login failed' });
    if (!r.data || !canUseAgrarius(r.data)) {
      return res.status(403).json({ error: 'Agrarius admin access required' });
    }
    const cookies = r.headers['set-cookie'];
    if (cookies) res.setHeader('Set-Cookie', cookies.map(normalizeWotCookie));
    return res.json({ ok: true, username: r.data && r.data.username, role: r.data && r.data.role });
  } catch (e) {
    return res.status(502).json({ error: 'Auth server nedostupný: ' + e.message });
  }
});

let _auditReady = false;
async function ensureAudit() {
  if (_auditReady) return;
  await db.query(
    'CREATE TABLE IF NOT EXISTS ftbquests_reset_audit (' +
    'id BIGINT PRIMARY KEY AUTO_INCREMENT, ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP, actor VARCHAR(64), ' +
    'scope VARCHAR(16), target_id VARCHAR(64), mode VARCHAR(16), chapter_id VARCHAR(16) NULL, ' +
    'quest_id VARCHAR(16) NULL, include_rewards TINYINT, include_ranks TINYINT, immediate TINYINT, summary TEXT) ' +
    'ENGINE=InnoDB DEFAULT CHARSET=utf8mb4'
  );
  _auditReady = true;
}

router.get('/api/agrarius/status', async (req, res) => {
  if (!(await requireWotAgrarius(req, res))) return;
  const p = await db.ping();
  let tables = [];
  try { tables = (await db.query('SHOW TABLES')).map((r) => Object.values(r)[0]); } catch (e) { p.tablesError = e.message; }
  res.json({ env: 'test', ...p, tables });
});

router.get('/api/agrarius/questbook', async (req, res) => {
  if (!(await requireWotAgrarius(req, res))) return;
  try { res.json(await questbook.getQuestbook(false)); }
  catch (e) { res.status(502).json({ error: 'questbook load failed: ' + e.message }); }
});

router.post('/api/agrarius/questbook/refresh', async (req, res) => {
  if (!(await requireWotAgrarius(req, res))) return;
  try { res.json(await questbook.getQuestbook(true)); }
  catch (e) { res.status(502).json({ error: 'refresh failed: ' + e.message }); }
});

router.get('/api/agrarius/targets', async (req, res) => {
  if (!(await requireWotAgrarius(req, res))) return;
  const raw = String(req.query.q || '').trim().slice(0, 40);
  const q = '%' + raw + '%';
  const limit = raw ? 80 : 200;
  try {
    const players = await db.query(
      'SELECT n.player_uuid uuid, n.player_name name, m.team_id teamId FROM ftbquests_player_names n ' +
      'LEFT JOIN ftbquests_team_membership m ON m.player_uuid=n.player_uuid WHERE n.player_name LIKE ? ' +
      'ORDER BY n.player_name LIMIT ?', [q, limit]);
    const teams = await db.query(
      'SELECT team_id teamId, team_name name, owner_uuid owner FROM ftbquests_team_info WHERE deleted=0 AND team_name LIKE ? ' +
      'ORDER BY team_name LIMIT ?', [q, limit]);
    res.json({ players, teams, query: raw, limit });
  } catch (e) { res.status(502).json({ error: e.message }); }
});

async function resolveTeams(scope, targetId) {
  if (scope === 'TEAM') {
    const members = await db.query('SELECT player_uuid FROM ftbquests_team_membership WHERE team_id=?', [targetId]);
    return { teamIds: [targetId], memberUuids: members.map((m) => m.player_uuid) };
  }
  const row = (await db.query('SELECT team_id FROM ftbquests_team_membership WHERE player_uuid=?', [targetId]))[0];
  const teamId = row ? row.team_id : targetId;
  return { teamIds: [teamId], memberUuids: [targetId] };
}

function resetFlags(resetKind, includeRanks) {
  if (resetKind === 'REWARDS_ONLY') return { stripProgress: false, deleteRewards: true, deleteRanks: false };
  if (resetKind === 'PROGRESS_ONLY') return { stripProgress: true, deleteRewards: false, deleteRanks: false };
  return { stripProgress: true, deleteRewards: true, deleteRanks: !!includeRanks };
}

router.post('/api/agrarius/reset', async (req, res) => {
  const who = await requireWotAgrarius(req, res);
  if (!who) return;
  const { scope, targetId, mode, chapterId, questId, includeRanks, immediate } = req.body || {};
  const resetKind = String((req.body || {}).resetKind || 'ALL').toUpperCase();
  if (!['PLAYER', 'TEAM'].includes(scope) || !targetId || !['FULL', 'CHAPTER', 'QUEST'].includes(mode)) {
    return res.status(400).json({ error: 'bad params' });
  }
  if (!['ALL', 'FULL', 'CHAPTER_ALL', 'PROGRESS_ONLY', 'REWARDS_ONLY'].includes(resetKind)) {
    return res.status(400).json({ error: 'bad reset kind' });
  }
  const flags = resetFlags(resetKind, includeRanks);
  await ensureAudit();
  let progressIds = null, rewardSet = null, rankQuestIds = null;
  if (mode !== 'FULL') {
    const maps = questbook.buildIdMaps(await questbook.getQuestbook(false));
    const entry = mode === 'CHAPTER' ? maps.byChapter[String(chapterId).toUpperCase()] : maps.byQuest[String(questId).toUpperCase()];
    if (!entry) return res.status(400).json({ error: 'unknown chapter/quest id' });
    progressIds = entry.progressIds;
    rewardSet = new Set(entry.rewardIds);
    rankQuestIds = mode === 'QUEST' ? [String(questId).toUpperCase()] : entry.progressIds;
  }
  const { teamIds, memberUuids } = await resolveTeams(scope, targetId);
  const pool = db.getPool();
  const conn = await pool.getConnection();
  const summary = { resetKind, includeRewards: flags.deleteRewards, includeRanks: flags.deleteRanks, teamIds, members: memberUuids.length, blobs: 0, rewardRows: 0, rankRows: 0, published: [] };
  try {
    await conn.beginTransaction();
    for (const teamId of teamIds) {
      const [rows] = await conn.execute('SELECT data, revision FROM ftbquests_teamdata WHERE team_id=? FOR UPDATE', [teamId]);
      if (rows.length && flags.stripProgress) {
        const tag = await blob.readBlob(rows[0].data);
        if (mode === 'FULL') blob.stripAll(tag);
        else blob.stripKeys(tag, progressIds, rewardSet);
        const out = blob.writeBlob(tag);
        const hash = blob.sha256(out);
        await conn.execute(
          'UPDATE ftbquests_teamdata SET data=?, data_hash=UNHEX(?), revision=revision+1, server_id=? WHERE team_id=?',
          [out, hash, 'web-admin', teamId]);
        const [revRows] = await conn.execute('SELECT revision FROM ftbquests_teamdata WHERE team_id=?', [teamId]);
        summary.blobs++;
        summary._lastHash = hash;
        summary._lastRevision = revRows[0] ? Number(revRows[0].revision) : 0;
      }
      if (flags.deleteRewards) {
        const ids = [teamId, ...memberUuids];
        const ph = ids.map(() => '?').join(',');
        if (mode === 'FULL') {
          const r = await conn.execute(`DELETE FROM ftbquests_reward_claim_scopes WHERE scope_uuid IN (${ph})`, ids);
          summary.rewardRows += r[0].affectedRows;
          await conn.execute('DELETE FROM ftbquests_reward_claims WHERE team_id=?', [teamId]);
        } else if (rewardSet.size) {
          const rids = [...rewardSet].map((h) => BigInt('0x' + h).toString());
          const rph = rids.map(() => '?').join(',');
          const r = await conn.execute(
            `DELETE FROM ftbquests_reward_claim_scopes WHERE scope_uuid IN (${ph}) AND reward_id IN (${rph})`, [...ids, ...rids]);
          summary.rewardRows += r[0].affectedRows;
        }
      }
      if (flags.deleteRanks) {
        if (mode === 'FULL') {
          const ph = memberUuids.map(() => '?').join(',') || 'NULL';
          if (memberUuids.length) {
            const r = await conn.execute(`DELETE FROM ftbquests_rank_progress WHERE player_uuid IN (${ph})`, memberUuids);
            summary.rankRows += r[0].affectedRows;
          }
        } else if (rankQuestIds && rankQuestIds.length) {
          const qids = rankQuestIds.map((h) => BigInt('0x' + h).toString());
          const qph = qids.map(() => '?').join(',');
          const mph = memberUuids.map(() => '?').join(',');
          if (memberUuids.length) {
            const r = await conn.execute(
              `DELETE FROM ftbquests_rank_progress WHERE player_uuid IN (${mph}) AND quest_id IN (${qph})`, [...memberUuids, ...qids]);
            summary.rankRows += r[0].affectedRows;
          }
        }
      }
    }
    await conn.commit();
  } catch (e) {
    await conn.rollback();
    conn.release();
    return res.status(500).json({ error: 'reset tx failed: ' + e.message });
  }
  conn.release();

  if (immediate) {
    for (const teamId of teamIds) {
      try { await db.publishReset(teamId, summary._lastRevision || 0, summary._lastHash || '', { reason: 'reset', forceReplace: true }); summary.published.push(teamId); }
      catch (e) { summary.publishError = e.message; }
    }
  }
  try {
    await db.query(
      'INSERT INTO ftbquests_reset_audit (actor,scope,target_id,mode,chapter_id,quest_id,include_rewards,include_ranks,immediate,summary) VALUES (?,?,?,?,?,?,?,?,?,?)',
      [who.username, scope, String(targetId), mode, chapterId || null, questId || null,
        flags.deleteRewards ? 1 : 0, flags.deleteRanks ? 1 : 0, immediate ? 1 : 0, JSON.stringify(summary)]);
  } catch (e) { summary.auditError = e.message; }
  res.json({ ok: true, cleared: summary });
});

router.get('/api/agrarius/progress', async (req, res) => {
  if (!(await requireWotAgrarius(req, res))) return;
  const scope = String(req.query.scope || 'PLAYER').toUpperCase();
  const targetId = String(req.query.targetId || '').trim();
  if (!targetId || !['PLAYER', 'TEAM'].includes(scope)) return res.status(400).json({ error: 'bad params' });
  try {
    const { teamIds } = await resolveTeams(scope, targetId);
    const completed = new Set();
    const started = new Set();
    for (const teamId of teamIds) {
      const teamBlob = await db.getTeamDataBlob(teamId);
      if (!teamBlob) continue;
      const tag = await blob.readBlob(teamBlob);
      const p = blob.parseProgress(tag);
      (p.completed || []).forEach((id) => completed.add(String(id).toUpperCase()));
      (p.started || []).forEach((id) => started.add(String(id).toUpperCase()));
    }
    res.setHeader('Cache-Control', 'no-store');
    res.json({
      teamIds,
      completed: Array.from(completed),
      started: Array.from(started),
    });
  } catch (e) { res.status(502).json({ error: e.message }); }
});

router.get('/api/agrarius/audit', async (req, res) => {
  if (!(await requireWotAgrarius(req, res))) return;
  try { await ensureAudit(); res.json(await db.query('SELECT * FROM ftbquests_reset_audit ORDER BY id DESC LIMIT 50')); }
  catch (e) { res.status(502).json({ error: e.message }); }
});

const PUBLIC = path.join(__dirname, 'public');
const TEXTURE_DIR = process.env.AGR_TEXTURE_DIR
  || path.join(__dirname, '..', 'agr-textures')
  || path.join(__dirname, 'public', 'textures');

function resolveTexture(mod, name, type) {
  const safe = (s) => String(s || '').replace(/[^a-z0-9_-]/gi, '').toLowerCase();
  const m = safe(mod); const n = safe(name);
  if (!m || !n) return null;
  const order = type === 'block' ? ['block', 'item'] : ['item', 'block'];
  for (const sub of order) {
    const p = path.join(TEXTURE_DIR, m, sub, n + '.png');
    if (fs.existsSync(p)) return p;
    const flat = path.join(TEXTURE_DIR, m, n + '.png');
    if (fs.existsSync(flat)) return flat;
  }
  if (n.includes('_')) {
    const stripped = n.replace(/_/g, '');
    for (const sub of order) {
      const p = path.join(TEXTURE_DIR, m, sub, stripped + '.png');
      if (fs.existsSync(p)) return p;
    }
  }
  return null;
}

router.get('/agrarius/textures/:mod/:name', (req, res) => {
  const file = resolveTexture(req.params.mod, req.params.name, 'item');
  if (!file) return res.status(404).end();
  res.setHeader('Cache-Control', 'public, max-age=86400, immutable');
  res.setHeader('Content-Type', 'image/png');
  res.sendFile(file);
});

let _manifestCache = null;
let _manifestAt = 0;
function buildTextureManifest() {
  if (_manifestCache && Date.now() - _manifestAt < 300000) return _manifestCache;
  const set = [];
  try {
    for (const mod of fs.readdirSync(TEXTURE_DIR)) {
      const modDir = path.join(TEXTURE_DIR, mod);
      let stat;
      try { stat = fs.statSync(modDir); } catch (e) { continue; }
      if (!stat.isDirectory()) continue;
      for (const sub of ['item', 'block']) {
        const subDir = path.join(modDir, sub);
        try {
          for (const f of fs.readdirSync(subDir)) {
            if (f.endsWith('.png')) set.push(mod.toLowerCase() + ':' + f.slice(0, -4).toLowerCase());
          }
        } catch (e) { continue; }
      }
      try {
        for (const f of fs.readdirSync(modDir)) {
          if (f.endsWith('.png')) set.push(mod.toLowerCase() + ':' + f.slice(0, -4).toLowerCase());
        }
      } catch (e) { continue; }
    }
  } catch (e) { console.error('[agrarius] texture manifest scan failed', e.message); }
  _manifestCache = set;
  _manifestAt = Date.now();
  return _manifestCache;
}

router.get('/api/agrarius/textures/manifest', async (req, res) => {
  if (!(await requireWotAgrarius(req, res))) return;
  res.setHeader('Cache-Control', 'public, max-age=300');
  res.json({ available: buildTextureManifest() });
});

const LANG_DIR = path.join(__dirname, 'lang');
let _langCache = null;
let _langLoadedAt = 0;
function loadLang() {
  if (_langCache && Date.now() - _langLoadedAt < 60000) return _langCache;
  const out = { cs: {}, en: {} };
  for (const [lang, file] of [['cs', 'cs_cz.json'], ['en', 'en_us.json']]) {
    const p = path.join(LANG_DIR, file);
    if (fs.existsSync(p)) {
      try { out[lang] = JSON.parse(fs.readFileSync(p, 'utf8')); }
      catch (e) { console.error('[agrarius] lang parse fail', file, e.message); }
    }
  }
  _langCache = out;
  _langLoadedAt = Date.now();
  return out;
}

router.get('/api/agrarius/lang', async (req, res) => {
  if (!(await requireWotAgrarius(req, res))) return;
  try {
    const lang = loadLang();
    const book = await questbook.getQuestbook(false);
    const chapterKeys = {};
    for (const ch of book.chapters || []) {
      const id = String(ch.filename || ch.id || '');
      const key = `ftbquests.chapter.${id}.title`;
      if (lang.cs[key]) chapterKeys[key] = lang.cs[key];
      else if (lang.en[key]) chapterKeys[key] = lang.en[key];
    }
    for (const k of Object.keys(lang.cs)) {
      if (/^ftbquests\.chapter\.[^.]+\.title$/.test(k) && !chapterKeys[k]) chapterKeys[k] = lang.cs[k];
    }
    const groupKeys = {};
    for (const g of book.groups || []) {
      const m = String(g.title || '').match(/^\{?(ftbquests\.chapter_groups_[^}]+)\.title\}?$/);
      if (m) {
        const k = m[1] + '.title';
        if (lang.cs[k]) groupKeys[k] = lang.cs[k];
        else if (lang.en[k]) groupKeys[k] = lang.en[k];
      }
    }
    const groupById = {};
    for (const ch of book.chapters || []) {
      if (ch.groupId && ch.group) {
        const m = String(ch.group).match(/^\{?(ftbquests\.chapter_groups_[^}]+)\.title\}?$/);
        if (m && !groupById[ch.groupId]) {
          const k = m[1] + '.title';
          groupById[ch.groupId] = lang.cs[k] || lang.en[k] || ch.group;
        }
      }
    }
    res.setHeader('Cache-Control', 'public, max-age=60');
    res.json({ chapterKeys, groupKeys, groupById, counts: book.counts || {} });
  } catch (e) { res.status(502).json({ error: e.message }); }
});

router.use('/agrarius', express.static(PUBLIC, { maxAge: 0, setHeaders: (res) => res.setHeader('Cache-Control', 'no-store, must-revalidate') }));
router.get('/agrarius', (req, res) => res.sendFile(path.join(PUBLIC, 'index.html')));

module.exports = router;
