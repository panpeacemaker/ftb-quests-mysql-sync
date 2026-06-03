'use strict';
const zlib = require('zlib');
const crypto = require('crypto');
const nbt = require('prismarine-nbt');

const PROGRESS_MAPS = ['started', 'completed', 'task_progress', 'completion_count', 'repeatable'];

async function readBlob(buf) {
  const { parsed } = await nbt.parse(buf);
  return parsed;
}

function writeBlob(tag) {
  const uncompressed = nbt.writeUncompressed(tag, 'big');
  return zlib.gzipSync(uncompressed);
}

function sha256(buf) {
  return crypto.createHash('sha256').update(buf).digest('hex');
}

function clearCompound(tag, key) {
  if (tag.value && tag.value[key] && tag.value[key].type === 'compound') {
    tag.value[key].value = {};
    return true;
  }
  return false;
}

function removeFromCompound(tag, key, hexSet) {
  const node = tag.value && tag.value[key];
  if (!node || node.type !== 'compound') return 0;
  let removed = 0;
  for (const k of Object.keys(node.value)) {
    if (hexSet.has(k.toUpperCase())) { delete node.value[k]; removed++; }
  }
  return removed;
}

function stripAll(tag) {
  let n = 0;
  for (const m of PROGRESS_MAPS) if (clearCompound(tag, m)) n++;
  clearCompound(tag, 'player_data');
  if (tag.value && tag.value.rewards_blocked) tag.value.rewards_blocked.value = 0;
  return n;
}

// hexIds = quest+task ids to clear from progress maps. rewardHexSet = reward ids
// whose claimed_rewards entries must be dropped (claimed_rewards keys embed the
// reward id, so match by suffix/contains the 16-hex reward id).
function stripKeys(tag, hexIds, rewardHexSet) {
  const idSet = new Set((hexIds || []).map((s) => String(s).toUpperCase()));
  let removed = 0;
  for (const m of PROGRESS_MAPS) removed += removeFromCompound(tag, m, idSet);
  if (rewardHexSet && rewardHexSet.size) {
    const cr = tag.value && tag.value.claimed_rewards;
    if (cr && cr.type === 'compound') {
      for (const k of Object.keys(cr.value)) {
        const up = k.toUpperCase();
        for (const rid of rewardHexSet) {
          if (up.includes(rid)) { delete cr.value[k]; removed++; break; }
        }
      }
    }
  }
  return removed;
}

function mapKeys(node) {
  if (!node || node.type !== 'compound' || !node.value) return [];
  return Object.keys(node.value);
}

function parseProgress(tag) {
  const v = (tag && tag.value) || {};
  return {
    completed: mapKeys(v.completed),
    started: mapKeys(v.started),
    taskProgress: mapKeys(v.task_progress),
  };
}

module.exports = { readBlob, writeBlob, sha256, stripAll, stripKeys, parseProgress, PROGRESS_MAPS };
