'use strict';
// Agrarius (FTB Quests) DB + Redis access — TEST env only for now.
// Lazy connect so require() never crashes if DB/Redis are unreachable.
const mysql = require('mysql2/promise');
const Redis = require('ioredis');

const CFG = {
  host: process.env.AGR_DB_HOST || '127.0.0.1',
  port: Number(process.env.AGR_DB_PORT || 3306),
  user: process.env.AGR_DB_USER || 'agrarius',
  password: process.env.AGR_DB_PASS || '',
  database: process.env.AGR_DB_NAME || 'agrarius_test',
};
const REDIS = {
  host: process.env.AGR_REDIS_HOST || '127.0.0.1',
  port: Number(process.env.AGR_REDIS_PORT || 6379),
  password: process.env.AGR_REDIS_PASS || '',
};
const REDIS_CHANNEL = process.env.AGR_REDIS_CHANNEL || 'ftbquests:team:updated';

let _pool = null;
let _redis = null;

function getPool() {
  if (!_pool) {
    _pool = mysql.createPool({
      ...CFG,
      waitForConnections: true,
      connectionLimit: 5,
      maxIdle: 2,
      idleTimeout: 60000,
      enableKeepAlive: true,
    });
  }
  return _pool;
}

function getRedis() {
  if (!_redis) {
    _redis = new Redis({
      host: REDIS.host,
      port: REDIS.port,
      password: REDIS.password || undefined,
      lazyConnect: true,
      maxRetriesPerRequest: 2,
      retryStrategy: (t) => (t > 3 ? null : Math.min(t * 200, 1000)),
    });
    _redis.on('error', (e) => console.error('[agrarius] redis error:', e.message));
  }
  return _redis;
}

async function query(sql, params) {
  const [rows] = await getPool().execute(sql, params || []);
  return rows;
}

async function ping() {
  const out = { db: false, redis: false };
  try { await getPool().query('SELECT 1'); out.db = true; } catch (e) { out.dbError = e.message; }
  try {
    const r = getRedis();
    if (r.status !== 'ready' && r.status !== 'connecting') await r.connect().catch(() => {});
    const pong = await r.ping();
    out.redis = pong === 'PONG';
  } catch (e) { out.redisError = e.message; }
  return out;
}

async function publishReset(teamId, revision, hashHex, opts) {
  const r = getRedis();
  if (r.status !== 'ready' && r.status !== 'connecting') await r.connect().catch(() => {});
  const o = opts || {};
  const payload = JSON.stringify({
    eventId: require('crypto').randomUUID(),
    sourceServer: 'web-admin',
    entityType: 'quest_team',
    entityId: String(teamId),
    revision: Number(revision) || 0,
    hash: hashHex || '',
    reason: o.reason || 'reset',
    ...(o.forceReplace === true ? { forceReplace: true } : {}),
  });
  await r.publish(REDIS_CHANNEL, payload);
  return payload;
}

async function getTeamDataBlob(teamId) {
  const rows = await query('SELECT data FROM ftbquests_teamdata WHERE team_id=? LIMIT 1', [teamId]);
  return rows.length ? rows[0].data : null;
}

module.exports = { getPool, getRedis, query, ping, publishReset, getTeamDataBlob, REDIS_CHANNEL, CFG };
