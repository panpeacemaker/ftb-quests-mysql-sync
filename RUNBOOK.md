# ftb-quests-mysql-sync — Operations Runbook

Operational reference for the customer handoff (release `1.2.0`). Covers config,
boot smoke, dependency/license posture, secret-scan evidence, build reproducibility,
DB backup/restore, and bad-reset rollback.

Infra (current deployment, replace with customer's on handoff):
- agr1 = PVE `sky-pve3` → `pct exec 1013`; agr2 = `pct exec 1014`. Dir `/opt/agrarius`.
- MySQL/MariaDB `agrarius_test` @ `192.168.88.245:3306`; Redis @ `192.168.88.245:6379`.
- Same JAR on both backends; `serverId` differs (`agr1`/`agr2`).

> Commands that touch agr1/agr2 or the DB run over SSH/DB and are OPS tasks. Values
> shown as `<...>` come from the live TOML; never paste real secrets into this file.

---

## 1. Configuration

Runtime path on each backend: `/opt/agrarius/config/ftbquestssync-common.toml`
(Forge COMMON config; the previous `ftbquestssync-server.toml` name is no longer
used by the mod).

The mod uses Forge's `COMMON` config type intentionally. `SERVER`-type configs are
synced to connecting clients and would leak MySQL/Redis credentials; `COMMON` loads
globally on the server side only.

Config key set (redact `password`/`redisPassword` to `***` when capturing):
`[mysql]` host, port, database, username, password, maxPoolSize, minIdle, useSsl,
allowPublicKeyRetrieval · `[redis]` redisHost, redisPort, redisPassword ·
`[server]` serverId · `[features]` syncQuests, syncTeams, syncChunks,
chunkSeedOnStart, chunkCanonicalServerId, chunkForceLoadSync, sendFullTeamData ·
`[policy]` mode, soloChapterIds, repeatableSoloChapterIds, soloQuestIds,
soloTaskIds, syncSoloProgressPerPlayer, soloRewardsPerPlayer,
teamRewardsDedupGlobal, rewardFailClosed, teamClaimChapterIds,
teamSharedChapterIds, rankBonuses · `[migration]` runOnBoot, redisKeyPrefix,
redisDb, sourceMysqlHost, sourceMysqlPort, sourceMysqlDatabase,
sourceMysqlUsername, sourceMysqlPassword, sourcePlayersTable, sourceDataTable,
sourceCreatedAtColumn, sourceIdPlayerColumn, sourceDataColumn, sourceUuidColumn,
sourceIdColumn, serverIdTag, maxPlayers, dryRun, maxBlobBytes, maxSnbtBytes,
overwriteExisting, runOnServerId, markerDir, remapUuids, usercachePath.

\* `conflictPolicy` and `sendDeltaPackets` are DEPRECATED/inert as of 1.1.9 — the
old parser ignored them and the new Forge spec does not define them; they have no
runtime effect. Safe to leave or delete from TOML.

Migration from the legacy flat `ftbquestssync-server.toml`: on first server start,
if the new Forge file still has defaults (blank MySQL password and blank serverId)
and `/opt/agrarius/config/ftbquestssync-server.toml` exists, the mod copies legacy
values into the new file, saves it, and continues using the new file. The legacy
file is left on disk and is no longer read. To force a clean migration, ensure the
new `ftbquestssync-common.toml` does not exist or contains only defaults, and keep
the legacy file in place for the first restart.

Capture live config (OPS, read-only):
```
sudo pct exec 1013 -- cat /opt/agrarius/config/ftbquestssync-common.toml   # repeat 1014
```
Expected per-backend: identical keys; `serverId=agr1` (1013) / `serverId=agr2` (1014).
Current hardened posture (verified 2026-06-08): `repeatableSoloChapterIds=[]` on both.

---

## 2. Boot smoke test

After (re)start, capture the 4 readiness lines (OPS):
```
sudo pct exec 1013 -- bash -lc "grep -E 'MySQL ready:|Redis ready:|TeamSync Redis ready:|FTB Quests Sync .* ready' /opt/agrarius/logs/latest.log | tail -8"   # repeat 1014
```
Expected line shapes (formats from source: MySQLBackend.java:438, RedisSync.java:95,
TeamSync.java:124, FTBQuestsSync.java:59 — version token tracks the release):
```
MySQL ready: <host>:3306/<db> user=<user> pool=<minIdle>/<maxPool>
Redis ready: <host>:6379 channel=agrarius:quests:team-updated serverId=agr1
TeamSync Redis ready: channel=ftbquests:team:membership
FTB Quests Sync 1.2.0 ready (mysqlAvailable=true, redisEnabled=true, teamsRedisEnabled=true, serverId=agr1)
```
Gate: all 4 present; serverId correct per CT. If the JAR still logs `1.1.9`, the
1.2.0 build has not shipped — fail the gate.

---

## 3. Database backup / restore

The mod auto-creates 6 tables: `ftbquests_teamdata`, `ftbquests_reward_claims`,
`ftbquests_reward_claim_scopes`, `ftbquests_rank_progress`, `ftbquests_team_info`,
`ftbquests_team_membership`. (`ftbquests_reset_audit` is created by the web console.)
`team_id` is `CHAR(36)` (UUID string).

PER-TABLE dump (enables table-scoped rollback; each file is self-contained
DROP+CREATE+INSERT under mysqldump `--opt` defaults):
```
D=$(date +%F)
for t in ftbquests_teamdata ftbquests_reward_claims ftbquests_reward_claim_scopes ftbquests_rank_progress ftbquests_team_info ftbquests_team_membership; do
  mysqldump -h 192.168.88.245 -u <user> -p <db> "$t" > "ftb-$t-$D.sql"
done
```
Verify dump (each must print `1`):
```
for t in ftbquests_teamdata ftbquests_reward_claims ftbquests_reward_claim_scopes ftbquests_rank_progress ftbquests_team_info ftbquests_team_membership; do grep -c 'CREATE TABLE' "ftb-$t-<date>.sql"; done
```
Full restore:
```
for f in ftb-ftbquests_*-<date>.sql; do mysql -h 192.168.88.245 -u <user> -p <db> < "$f"; done
```
PRE-RESET COUNT (capture before any admin reset; 4 tables a reset mutates):
```
mysql -h 192.168.88.245 -u <user> -p <db> -N -e "SELECT (SELECT COUNT(*) FROM ftbquests_teamdata),(SELECT COUNT(*) FROM ftbquests_reward_claims),(SELECT COUNT(*) FROM ftbquests_reward_claim_scopes),(SELECT COUNT(*) FROM ftbquests_rank_progress);"
```

---

## 4. Bad admin-reset rollback

A `POST /api/agrarius/reset` mutates FOUR tables (routes.js:165-208): UPDATE
`ftbquests_teamdata` (rewrites `data`, `data_hash`, `revision=revision+1`,
`server_id='web-admin'`), DELETE `ftbquests_reward_claims` (FULL mode), DELETE
`ftbquests_reward_claim_scopes`, DELETE `ftbquests_rank_progress`.

Find the affected team UUID(s) — for `scope='PLAYER'` the team id is in the audit
`summary` JSON `teamIds[]` (NOT `target_id`, which is a player uuid):
```
mysql -h 192.168.88.245 -u <user> -p <db> -e "SELECT id, ts, scope, target_id, JSON_EXTRACT(summary,'\$.teamIds') AS team_ids, mode FROM ftbquests_reset_audit ORDER BY id DESC LIMIT 5;"
```

WARNING (live-prod): full-table restore reverts these 4 tables to DUMP-TIME for ALL
teams/players, not just the affected one. Run in a maintenance window, or take a
fresh per-table dump immediately BEFORE each admin reset to minimize the window.
Row-scoped restore of a LONGBLOB is not practical from a full dump.

STEP 1 — restore the 4 affected tables:
```
for t in ftbquests_teamdata ftbquests_reward_claims ftbquests_reward_claim_scopes ftbquests_rank_progress; do mysql -h 192.168.88.245 -u <user> -p <db> < "ftb-$t-<date>.sql"; done
```
STEP 2 — force peers to reload (team_id is CHAR(36), match as string, no UNHEX):
```
mysql -h 192.168.88.245 -u <user> -p <db> -e "UPDATE ftbquests_teamdata SET revision=revision+1 WHERE team_id='<uuid>';"
```
then restart both backends (Section 2 confirms reload), or wait for the next write to publish via Redis.

STEP 3 — verify (all 4 counts must equal the PRE-RESET COUNT from Section 3):
```
mysql -h 192.168.88.245 -u <user> -p <db> -N -e "SELECT (SELECT COUNT(*) FROM ftbquests_teamdata),(SELECT COUNT(*) FROM ftbquests_reward_claims),(SELECT COUNT(*) FROM ftbquests_reward_claim_scopes),(SELECT COUNT(*) FROM ftbquests_rank_progress);"
```

---

## 5. Build reproducibility

Toolchain: JDK 17 · Forge `1.20.1-47.4.10` · FTB libs in `libs/`
(ftb-quests 2001.4.18, ftb-chunks 2001.3.6, ftb-teams 2001.3.2, ftb-library 2001.2.12,
architectury 9.2.14) · Node ≥18 (web companion).

Mod build:
```
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # MUST be JDK 17; system default may be newer and breaks the build
./gradlew clean reobfShadowJar
# output: build/libs/ftb-quests-mysql-sync-1.2.0.jar
sha256sum build/libs/ftb-quests-mysql-sync-1.2.0.jar   # record; must match on agr1 + agr2
```
RELEASE artifact (v1.2.0, `./gradlew clean reobfShadowJar`, JDK 17.0.19 — this is the jar to DEPLOY):
`6c4076f0dc0127fc22aba4fec04e40a4780adfce574f20c8520d122098af9a0e  ftb-quests-mysql-sync-1.2.0.jar`
Published at GitHub release `v1.2.0`. Deploy the SAME jar to agr1 + agr2; verify identical SHA256.
(Dev jar from `./gradlew clean build` differs intentionally: `38a104ac…4735`.)
Web install (reproducible):
```
cd web && npm ci    # requires committed package-lock.json
```

Deployment: build JAR → copy SAME jar to both backends → verify identical SHA256 →
verify TOML on both → restart → run Section 2 boot smoke. Deploy to a staging/test
world before prod.

Rollback (deploy): `git checkout v1.1.8-handoff-baseline` + redeploy the prior JAR.
Code-only changes do not alter the DB; the web app is stateless.

---

## 6. Dependency & license audit

Mod (shaded into the JAR, relocated):
| Dependency | Version | License | CVE status |
|---|---|---|---|
| HikariCP | 5.1.0 | Apache-2.0 | none known |
| mysql-connector-j | 8.3.0 | GPLv2 + FOSS exception | no blocking CVE (safe from CVE-2023-21971/22102) |
| jedis | 5.1.0 | MIT | AIKIDO-2026-10791 — missing TLS hostname verification on legacy `ssl(true)` (fixed 7.5.0). LOW: current Redis uses plain (no TLS). Upgrade if TLS to Redis is enabled. |

Mod (compileOnly, NOT shipped): Forge, FTB quests/chunks/teams/library, architectury.
Note: `build.gradle` shadowJar excludes `LICENSE`/`NOTICE` (lines 109-110) — for the
GPLv2+FOSS mysql-connector, confirm notice-preservation obligations before redistribution.

Web (`web/package.json`): express, axios, ioredis, mysql2, prismarine-nbt (added 1.1.9)
+ any header/rate-limit lib added during hardening. Run and record:
```
cd web && npm audit            # act on HIGH/CRITICAL only
```
`npm audit` result (fill on run): __________ (date: ______).

---

## 7. Secret-scan evidence

- Inline git-history scan (`git log -p | grep` for password/IP/key patterns): CLEAN,
  2026-06-08 (only documentation references to the word "secrets").
- RESIDUAL RISK: a dedicated scanner (`gitleaks`/`trufflehog`) was NOT available in the
  hardening environment. Before final handoff, run and record:
  ```
  gitleaks detect --no-banner --redact --source .
  ```
  Result: `gitleaks v8` (docker `zricethezav/gitleaks:latest`) — **no leaks found**, 34 commits
  / 1.24 MB scanned, 2026-06-08. Secret-scan gate GREEN.

---

## 8. Known limitations (document for customer)

- Web auth has no local session TTL — relies on the upstream WOT cookie lifetime.
- Custom-header CSRF on `/reset` is effective absent CORS but is defeated by same-origin
  XSS (inherent to the pattern); token-CSRF is a roadmap item.
- Redis channel messages are unsigned — an attacker with Redis access could forge reset
  events. Network-isolate Redis; HMAC signing is a roadmap item.
- `MembershipCache` is populated but not time-evicted (bounded by player set; monitor on
  long-uptime servers). Eviction is a roadmap item.
- `dbExecutor` is single-thread, queue 256, AbortPolicy — watch logs for
  `RejectedExecutionException` under heavy write bursts.

## 9. Deferred technical-debt map (post-handoff roadmap)

God-objects recommended for future decomposition (NOT required for handoff):
- `MySQLBackend.java` (2197 LOC) → ConnectionProvider, SchemaManager, TeamDataStore,
  RewardClaimStore, TeamMembershipStore, PlayerNameStore, RankProgressStore,
  ChunkClaimStore, TeamInviteStore, TeamLoadStateRegistry.
- `TeamSync.java` (883), `RedisSync.java` (804, esp. 188-line `applyRemoteUpdate` +
  `mergeTeamDataNbt`), `web/public/agrarius.js` (760), `web/routes.js` (400).
Prerequisite before touching sync core: golden/property tests for `mergeTeamDataNbt`.
