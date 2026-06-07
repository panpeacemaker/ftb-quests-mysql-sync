'use strict';
const path = require('path');
const { execFile } = require('child_process');
const { promisify } = require('util');
const execFileAsync = promisify(execFile);

function parseSnbt(text) {
  let i = 0;
  const s = text;
  function skipWs() {
    while (i < s.length) {
      const c = s[i];
      if (c === ' ' || c === '\t' || c === '\n' || c === '\r' || c === ',') { i++; continue; }
      if (c === '#') { while (i < s.length && s[i] !== '\n') i++; continue; }
      break;
    }
  }
  function parseValue() {
    skipWs();
    const c = s[i];
    if (c === '{') return parseObject();
    if (c === '[') return parseArray();
    if (c === '"' || c === "'") return parseString(c);
    return parseLiteral();
  }
  function parseObject() {
    const obj = {};
    i++;
    skipWs();
    while (i < s.length && s[i] !== '}') {
      skipWs();
      let key;
      if (s[i] === '"' || s[i] === "'") key = parseString(s[i]);
      else { const start = i; while (i < s.length && !':}'.includes(s[i]) && !' \t\n\r'.includes(s[i])) i++; key = s.slice(start, i).trim(); }
      skipWs();
      if (s[i] === ':') i++;
      obj[key] = parseValue();
      skipWs();
    }
    i++;
    return obj;
  }
  function parseArray() {
    const arr = [];
    i++;
    skipWs();
    if (s[i] === 'I' || s[i] === 'B' || s[i] === 'L') {
      if (s[i + 1] === ';') { i += 2; }
    }
    skipWs();
    while (i < s.length && s[i] !== ']') { arr.push(parseValue()); skipWs(); }
    i++;
    return arr;
  }
  function parseString(q) {
    i++;
    let out = '';
    while (i < s.length && s[i] !== q) {
      if (s[i] === '\\') { out += s[i + 1]; i += 2; continue; }
      out += s[i]; i++;
    }
    i++;
    return out;
  }
  function parseLiteral() {
    const start = i;
    while (i < s.length && !',}] \t\n\r'.includes(s[i])) i++;
    let tok = s.slice(start, i).trim();
    if (tok === 'true') return true;
    if (tok === 'false') return false;
    const num = tok.replace(/[dDfFbBsSlL]$/, '');
    if (/^-?\d+(\.\d+)?$/.test(num)) return Number(num);
    return tok;
  }
  return parseValue();
}

function asId(value) {
  return String(value || '').toUpperCase();
}

function asNumber(value, fallback) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function textList(value) {
  if (Array.isArray(value)) return value.map((x) => String(x)).filter(Boolean);
  return value ? [String(value)] : [];
}

function summarizeItem(item) {
  if (!item) return null;
  if (typeof item === 'string') return { id: item, label: item };
  if (typeof item === 'object') {
    const id = item.id || (item.tag && item.tag.value) || null;
    return {
      id: id ? String(id) : null,
      label: id ? String(id) : JSON.stringify(item).slice(0, 120),
      count: item.Count || item.count || null,
    };
  }
  return { id: null, label: String(item) };
}

function summarizeTask(task) {
  return {
    id: asId(task.id),
    type: task.type || null,
    title: task.title || null,
    item: summarizeItem(task.item),
    count: task.count || null,
  };
}

function summarizeReward(reward) {
  return {
    id: asId(reward.id),
    type: reward.type || null,
    title: reward.title || null,
    item: summarizeItem(reward.item),
    count: reward.count || null,
    teamReward: !!reward.team_reward,
  };
}

function groupTitle(groupNames, groupId) {
  return groupId ? (groupNames[groupId] || groupId) : null;
}

function chapterToNode(ch, groupNames) {
  const quests = Array.isArray(ch.quests) ? ch.quests : [];
  const groupId = asId(ch.group);
  const defaultShape = ch.default_quest_shape || '';
  return {
    id: asId(ch.id),
    filename: ch.filename || null,
    title: ch.title || ch.filename || String(ch.id || ''),
    groupId: groupId || null,
    group: groupTitle(groupNames, groupId),
    icon: ch.icon || null,
    orderIndex: ch.order_index || 0,
    defaultQuestShape: defaultShape,
    quests: quests.map((q) => {
      const tasks = (Array.isArray(q.tasks) ? q.tasks : []).map(summarizeTask).filter((t) => t.id);
      const rewards = (Array.isArray(q.rewards) ? q.rewards : []).map(summarizeReward).filter((r) => r.id);
      return {
        id: asId(q.id),
        title: q.title || q.subtitle || String(q.id || ''),
        subtitle: q.subtitle || null,
        description: textList(q.description),
        icon: q.icon || (tasks[0] && tasks[0].item && tasks[0].item.id) || (rewards[0] && rewards[0].item && rewards[0].item.id) || null,
        x: asNumber(q.x, 0),
        y: asNumber(q.y, 0),
        shape: q.shape || defaultShape || 'hexagon',
        size: asNumber(q.size, 1),
        dependencies: (Array.isArray(q.dependencies) ? q.dependencies : []).map(asId).filter(Boolean),
        tasks,
        rewards,
        taskIds: tasks.map((t) => t.id),
        rewardIds: rewards.map((r) => r.id),
      };
    }),
  };
}

function parseGroups(raw) {
  const groupNames = {};
  const groups = [];
  const g = parseSnbt(raw);
  (g.chapter_groups || []).forEach((x) => {
    const id = asId(x.id);
    if (!id) return;
    const title = x.title || x.name || id;
    groupNames[id] = title;
    groups.push({ id, title });
  });
  return { groupNames, groups };
}

function finishBook(chapters, groups, source) {
  chapters.sort((a, b) => (a.orderIndex - b.orderIndex) || String(a.title).localeCompare(String(b.title), 'cs'));
  const allQuests = [];
  chapters.forEach((chapter) => {
    chapter.quests.forEach((quest) => {
      allQuests.push({
        id: quest.id,
        title: quest.title,
        chapterId: chapter.id,
        chapterTitle: chapter.title,
        groupId: chapter.groupId,
        groupTitle: chapter.group,
        x: quest.x,
        y: quest.y,
      });
    });
  });
  return {
    groups,
    chapters,
    allQuests,
    counts: { groups: groups.length, chapters: chapters.length, quests: allQuests.length },
    loadedAt: new Date().toISOString(),
    ...(source ? { source } : {}),
  };
}

let _cache = null;

async function loadLiveSnbt() {
  // Prefer a LOCAL snapshot dir (deployed onto the web host) so production
  // needs no SSH to a Proxmox host. Falls back to live ssh+pct on dev only;
  // both AGR_QUEST_CT and AGR_PVE_HOST must be set or this path returns empty.
  const local = process.env.AGR_QUEST_LOCAL;
  if (local) {
    try { return loadFromDir(local); }
    catch (e) { console.error('[agrarius] local snapshot read failed, trying live:', e.message); }
  }
  const ct = process.env.AGR_QUEST_CT;
  const node = process.env.AGR_PVE_HOST;
  if (!ct || !node) {
    console.error('[agrarius] live SNBT fetch disabled: set AGR_QUEST_LOCAL or both AGR_QUEST_CT + AGR_PVE_HOST');
    return finishBook([], [], 'unconfigured');
  }
  const base = process.env.AGR_QUEST_DIR || '/opt/agrarius/config/ftbquests/quests';
  if (!/^[A-Za-z0-9._/-]+$/.test(base)) {
    console.error('[agrarius] invalid AGR_QUEST_DIR, refusing live fetch:', base);
    return finishBook([], [], 'unconfigured');
  }
  const groupsRaw = await sh(node, ct, `cat ${base}/chapter_groups.snbt 2>/dev/null`);
  let groupNames = {};
  let groups = [];
  try {
    const parsedGroups = parseGroups(groupsRaw);
    groupNames = parsedGroups.groupNames;
    groups = parsedGroups.groups;
  } catch (e) { console.error('[agrarius] chapter_groups parse fail', e.message); }
  const list = (await sh(node, ct, `ls ${base}/chapters/*.snbt 2>/dev/null`)).split('\n').map((x) => x.trim()).filter(Boolean);
  const chapters = [];
  for (const file of list) {
    // Only cat paths that look like real snbt files; reject any shell metachars
    // that could ride in through the `ls` output.
    if (!/^[A-Za-z0-9._/-]+\.snbt$/.test(file)) { console.error('[agrarius] skip unsafe snbt path', file); continue; }
    const raw = await sh(node, ct, `cat ${file} 2>/dev/null`);
    try { chapters.push(chapterToNode(parseSnbt(raw), groupNames)); }
    catch (e) { console.error('[agrarius] questbook parse fail', file, e.message); }
  }
  return finishBook(chapters, groups);
}

function loadFromDir(dir) {
  const fs = require('fs');
  let groupNames = {};
  let groups = [];
  try {
    const parsedGroups = parseGroups(fs.readFileSync(path.join(dir, 'chapter_groups.snbt'), 'utf8'));
    groupNames = parsedGroups.groupNames;
    groups = parsedGroups.groups;
  } catch (e) { console.error('[agrarius] local chapter_groups parse fail', e.message); }
  const chaptersDir = path.join(dir, 'chapters');
  const chapters = [];
  for (const f of fs.readdirSync(chaptersDir).filter((x) => x.endsWith('.snbt'))) {
    try { chapters.push(chapterToNode(parseSnbt(fs.readFileSync(path.join(chaptersDir, f), 'utf8')), groupNames)); }
    catch (e) { console.error('[agrarius] local questbook parse fail', f, e.message); }
  }
  return finishBook(chapters, groups, 'local');
}

async function sh(node, ct, cmd) {
  // Hardening: node + ct originate from server env (AGR_PVE_HOST / AGR_QUEST_CT),
  // never from HTTP input. Validate defensively so a misconfigured env can never
  // inject into the remote shell string that bash -lc evaluates on the PVE host.
  if (!/^[A-Za-z0-9._@-]+$/.test(String(node || ''))) throw new Error('invalid AGR_PVE_HOST');
  if (!/^[0-9]+$/.test(String(ct || ''))) throw new Error('invalid AGR_QUEST_CT (must be numeric)');
  const { stdout } = await execFileAsync('ssh', ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=15', String(node),
    `pct exec ${ct} -- bash -lc ${JSON.stringify(cmd)}`], { maxBuffer: 20 * 1024 * 1024 });
  return stdout;
}

async function getQuestbook(force) {
  if (_cache && !force) return _cache;
  _cache = await loadLiveSnbt();
  return _cache;
}

function buildIdMaps(book) {
  const byChapter = {};
  const byQuest = {};
  for (const ch of book.chapters) {
    const allQuestIds = [];
    const allTaskIds = [];
    const allRewardIds = [];
    for (const q of ch.quests) {
      allQuestIds.push(q.id);
      allTaskIds.push(...q.taskIds);
      allRewardIds.push(...q.rewardIds);
      byQuest[q.id] = { progressIds: [q.id, ...q.taskIds], rewardIds: q.rewardIds };
    }
    byChapter[ch.id] = { progressIds: [...allQuestIds, ...allTaskIds], rewardIds: allRewardIds };
  }
  return { byChapter, byQuest };
}

module.exports = { parseSnbt, getQuestbook, buildIdMaps, loadLiveSnbt };
