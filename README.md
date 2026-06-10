# ftb-quests-mysql-sync

Server-side Forge 1.20.1 mod for Agrarius. It keeps FTB Quests progress persistent across two backend servers (`agr1` and `agr2`), syncs canonical quest/team state through MySQL/MariaDB, uses Redis as an invalidation bus, and keeps rank/shop progress solo per player.

This repository is intentionally scoped to the mod project only. Runtime secrets, internal infrastructure identifiers, public/private IP addresses, and production port mappings are kept out of this tree.

## Current status

| Area | Status |
|---|---|
| Minecraft/Forge target | Forge 1.20.1 server-side mod |
| Current version | `1.2.0` |
| Build command | `./gradlew clean reobfShadowJar` |
| Output JAR | `build/libs/ftb-quests-mysql-sync-1.2.0.jar` |
| Deployment model | same JAR on `agr1` and `agr2` |
| Canonical storage | MySQL/MariaDB |
| Live invalidation | Redis pub/sub |
| Shared quests | remain team-shared |
| Rank/shop chapters | solo per-player via policy config |
| Reward protection | DB dedupe for shared and one-shot rewards |
| FTB Teams sync | party membership, owner, live color/name across servers (`syncTeams`, default off) |
| FTB Chunks sync | claimed + force-loaded chunks per team (`syncChunks`, default off) |
| Cross-server team mgmt | pending invites (consent), presence/online-dot, owner/officer actions |
| Legacy data migration | one-shot import of historical per-player quest data (`1.2.0`, default off) |
| Companion web viewer | read-only questbook viewer + admin reset console in `web/` (see `web/README.md`) |
| Server identity | explicit config, JVM arg, legacy key, or `luckperms.server` fallback |

## Problem this solves

Agrarius runs two Forge backends behind Velocity. Without a shared quest state, a player can complete progress on one backend and then reconnect/fail over to the other backend with stale or missing FTB Quests data.

The mod solves this by:

1. storing FTB Quests `TeamData` snapshots in MySQL/MariaDB,
2. publishing Redis events when a backend writes a new revision,
3. reloading canonical state on the other backend,
4. pushing FTB Quests refresh packets to connected players,
5. separating rank/shop progress from shared team progress where required.

## Runtime architecture

```text
Player
  -> Velocity
      -> agr1 / agr2 Forge backend
          -> ftb-quests-mysql-sync
              -> MySQL/MariaDB  (canonical quest/team data)
              -> Redis pub/sub  (change notification only)
```

MySQL/MariaDB is the source of truth. Redis is not authoritative; it only tells the other backend that a newer revision exists.

## Quest sync flow

**1. Player changes quest progress** — FTB Quests mutates `TeamData`; the mod hooks `TeamData.markDirty`, snapshots it, writes it to MySQL asynchronously, then publishes a Redis update with revision/hash.

**2. Remote backend receives the Redis update** — checks revision/hash, loads canonical data from MySQL, merges safe local/remote fields, and pushes FTB Quests refresh packets (`SyncTeamDataMessage`) to affected players.

**3. Player reconnects after crash/failover** — on login the backend reloads from MySQL before trusting local in-memory state, so any progress that reached MySQL before the crash is preserved.

## Database schema

Tables are created automatically on startup.

| Table | Purpose |
|---|---|
| `ftbquests_teamdata` | serialized FTB `TeamData`, revision, hash, source server |
| `ftbquests_reward_claims` | legacy reward claim guard |
| `ftbquests_reward_claim_scopes` | active scoped reward dedupe: `TEAM` or `PLAYER` |
| `ftbquests_rank_progress` | solo rank/shop progress per player |
| `ftbquests_team_info` | FTB Teams metadata |
| `ftbquests_team_membership` | FTB Teams membership data |

## Redis channels

```text
agrarius:quests:team-updated
ftbquests:team:membership
```

## Solo rank/shop policy

```toml
[policy]
mode = "blacklist"
soloChapterIds = ["3622ED01311E6763", "3CEC7F7BAD54E4C6"]
repeatableSoloChapterIds = ["3622ED01311E6763", "3CEC7F7BAD54E4C6"]
soloQuestIds = []
soloTaskIds = []
syncSoloProgressPerPlayer = true
soloRewardsPerPlayer = true
teamRewardsDedupGlobal = true
rewardFailClosed = true
```

`blacklist` means listed objects are solo and everything else remains team-shared. `whitelist` means listed objects are shared and everything else becomes solo. `repeatableSoloChapterIds` is a manual safety override for solo rank/shop chapters where vanilla FTB progress must still be allowed so the normal claim/payment flow can finish.

## Reward claim behavior and shop/rank fix

Reward claims are protected through `ftbquests_reward_claim_scopes`. Shared/team rewards and one-shot solo rewards still use DB dedupe. Repeatable solo/shop rewards bypass permanent DB reward-id dedupe and use vanilla FTB Quests repeatable/payment logic instead.

The shop bug had two parts. First, a repeatable shop purchase can reuse the same `reward_id`, so a permanent DB reward-id guard must not block it. Second, solo task progress previously cancelled vanilla FTB `setProgress`, which made the client look claimable while the server-side FTB quest was not actually complete. Current behavior allows vanilla progress for configured solo rank/shop chapters, keeps repeatable shop progress out of persistent rank-progress DB, and only deduplicates reward cases that should be one-shot.

## Server identity

Resolution order:

1. `-Dftbquestssync.server.id=agr1`
2. TOML `serverId = "agr1"`
3. legacy `-Dftbquestssync.serverId=agr1`
4. `-Dluckperms.server=agr1`
5. unstable fallback `unknown-<random>`

```java
System.getProperty("luckperms.server", "unknown")
```

## Configuration template

Runtime path: `/opt/agrarius/config/ftbquestssync-server.toml`

Template without secrets or infrastructure-specific addresses:

```toml
[mysql]
host = "DB_HOST"
port = 3306
database = "agrarius_test"
username = "agrarius"
password = "REPLACE_ME"
maxPoolSize = 5
minIdle = 2
useSsl = true
allowPublicKeyRetrieval = false

[redis]
redisHost = "REDIS_HOST"
redisPort = 6379
redisPassword = ""

[server]
serverId = "agr1"

[features]
syncQuests = true
syncTeams = true
sendFullTeamData = true
# sendDeltaPackets and conflictPolicy are removed/inert (ignored if present)

[policy]
mode = "blacklist"
soloChapterIds = ["3622ED01311E6763", "3CEC7F7BAD54E4C6"]
repeatableSoloChapterIds = ["3622ED01311E6763", "3CEC7F7BAD54E4C6"]
soloQuestIds = []
soloTaskIds = []
syncSoloProgressPerPlayer = true
soloRewardsPerPlayer = true
teamRewardsDedupGlobal = true
rewardFailClosed = true
```

For `agr2`, keep the same config but change `serverId = "agr2"`.

## Build

```bash
./gradlew clean build
```

Output: `build/libs/ftb-quests-mysql-sync-1.2.0.jar`

## Deployment checklist

1. Build the JAR.
2. Copy the same JAR to both Forge backends.
3. Verify SHA256 is identical on `agr1` and `agr2`.
4. Verify `/opt/agrarius/config/ftbquestssync-server.toml` on both backends.
5. Restart both backends.
6. Check logs for MySQL, Redis, TeamSync, and the correct `serverId`.

Expected log shape:

```text
MySQL ready: DB_HOST:3306/agrarius_test
Redis ready: REDIS_HOST:6379 channel=agrarius:quests:team-updated serverId=agr1
TeamSync Redis ready: channel=ftbquests:team:membership
FTB Quests Sync ready (mysqlAvailable=true, redisEnabled=true, teamsRedisEnabled=true, serverId=agr1)
```

## Failover reality

Guaranteed: quest progress that reached MySQL survives reconnect/failover and either backend can load the latest DB-backed state.

Not guaranteed: the final milliseconds before hard poweroff if an async DB write did not finish, world/block sync, or perfect live modded Velocity transfer without reconnect. Forge clients can sometimes hit `unregistered packet`; reconnecting through the proxy reloads canonical quest data.

---

# Legacy per-player quest-data migration

A **one-shot importer** that moves historical quest progress from an old per-player store (Markus's Stratos plugin) into this mod's tables. Run it **once** when first deploying onto a server that already has quest progress. After it finishes, the normal live-sync machinery takes over and the database is the source of truth.

The whole rollout is **two operator commands**:

```text
/ftbsync importteams     # run on BOTH agr1 and agr2 — copies each server's FTB Teams into the DB
/ftbsync migrate false   # run on ONE server only — copies player quest progress into the DB
```

> [!CAUTION]
> ## 🟡 The single most important rule: `usercache.json` must contain every player
>
> The old data is keyed by each player's **offline** UUID (name-based, from when the server ran `online-mode=false`). The live server logs players in under their **online/premium** UUID. The migrator bridges the two by matching the player **name** in `usercache.json`.
>
> **A player who is missing from `usercache.json` is NOT remapped — their quests, ranks and reward claims are written under the dead offline UUID and that player will see NOTHING until you refresh the usercache and migrate again.**
>
> Verified on real data: `Raketak_skaj` was in the usercache → remapped `af6c18a8…`(offline) → `aa1dfc43…`(online) → data appears correctly on login. A test player who was *not* in the usercache stayed under the offline UUID and his data was orphaned.
>
> **Before migrating:** confirm every player is in `/opt/agrarius/usercache.json`.
> **After migrating:** the log prints `===== MIGRATION SUMMARY ===== … uuidMissedNoUsercache=<N> …`. **That number must be `0`.** If it is not, the log names each missed player — refresh the usercache and re-run.

## How it works

### What it reads

A per-player ZIP archive containing a `quests.snbt` entry (the FTB Quests `TeamData` serialized as SNBT text). Other entries in the ZIP (`sophisticated_data.dat`, `playerdata.dat`, …) are ignored.

Two sources are tried in order; the first non-empty one wins per player:

1. **Redis** — key `<redisKeyPrefix><uuid>`, value = raw ZIP bytes. Default prefix `stratos:data:player:blob:`.
2. **Source MariaDB** — `core_player_data.data` (LONGBLOB = the same ZIP), joined to `core_players` for the UUID. The **newest** snapshot per player wins (`MAX(created_at)`, ties broken on highest `id`). Leave `sourceMysqlHost` empty to skip MariaDB and use Redis only.

> [!NOTE]
> On the live Agrarius setup the **source MariaDB is the same database the mod uses** (`agrarius_test` already holds `core_players` + `core_player_data`), and Redis may be empty — in that case the migrator simply reads everything from MariaDB. Both source and target sharing one database is supported and expected.

### What it writes

For each player:

| Step | Target table | Notes |
|---|---|---|
| Quest progress (started / completed / task progress) | `ftbquests_teamdata` | `server_id` = `migrator` so imported rows are distinguishable from live writes |
| Solo / rank / QoL progress | `ftbquests_rank_progress` | the runtime strips this out of the team blob on every save, so it must be migrated separately or ranks vanish |
| Already-claimed rewards | `ftbquests_reward_claim_scopes` | so a claimed reward can't be claimed again cross-server after migration |
| Team-reward claim keys → `NIL_UUID` | (inside the team blob) | so the **client GUI** shows migrated team rewards as already claimed instead of offering the claim button |
| Solo `team_info` + self-membership (OWNER) | `ftbquests_team_info` / `ftbquests_team_membership` | so a solo player's data resolves on their **first** login, not the second |

`/ftbsync importteams` separately walks the FTB Teams that exist on the running server (party / server / player teams) and writes them to `ftbquests_team_info` / `ftbquests_team_membership` with the **real** `serverId`. Because agr1 and agr2 host separate teams, run it on **both** peers.

### Solo vs party detection

A **solo** export's `uuid` field equals the player's own UUID → imported under `team_id = <player-uuid>`, with rank + reward + team-binding migration.

A **party** export carries the shared party UUID (different from the player UUID) → imported once under `team_id = <party-uuid>`; other members of the same party are de-duplicated to that one write. Party `team_info` / `membership` are **not** written here — FTB Teams materializes them on login.

> The legacy `name` suffix (`Display#af6c18a8`) is only an 8-char short id, so detection uses the blob's `uuid` field, never the name.

### Safety properties

- **Off by default.** `runOnBoot = false`; the mod behaves exactly like 1.1.9 until you opt in. The `/ftbsync migrate` command is rejected unless `runOnBoot = true`.
- **Idempotent.** A per-server marker file (`<markerDir>/ftbquestssync.migration.done.<serverId>`) prevents re-runs. The team-data write also skips when the content hash is unchanged, so a re-run never bumps the revision counter.
- **Fully reversible.** Imported rows are tagged `server_id = 'migrator'`; one `DELETE` removes them all (see [Rollback](#rollback)).
- **Won't write the marker on failure.** If any player fails or a `maxPlayers` cap is hit, the marker is not written and the next run retries.

## Rollout: step by step

### Step 1 — fill in the `[migration]` block

Edit `/opt/agrarius/config/ftbquestssync-server.toml`. Real Agrarius values shown:

```toml
[migration]
runOnBoot           = true                       # master switch; the /ftbsync commands need this on
redisKeyPrefix      = "stratos:data:player:blob:" # the Stratos plugin's actual key prefix
redisDb             = 0
remapUuids          = true                        # REQUIRED — server switched offline->online auth
usercachePath       = "/opt/agrarius/usercache.json"

# Source MariaDB = the same DB the mod already uses (reuse the [mysql] credentials)
sourceMysqlHost     = "DB_HOST"
sourceMysqlPort     = 3306
sourceMysqlDatabase = "agrarius_test"
sourceMysqlUsername = "agrarius"
sourceMysqlPassword = "REPLACE_ME"

serverIdTag         = "migrator"
markerDir           = "/opt/agrarius/config"
maxPlayers          = 0                            # 0 = no cap
dryRun              = false
```

Leave the `source*Table` / `source*Column` keys at their defaults — they already match the `core_players` / `core_player_data` schema. Override them only if your source schema differs.

> [!IMPORTANT]
> `runOnBoot = true` is just the **enable flag** for the `/ftbsync` commands. It does **not** force the migration to run on every boot — the marker file prevents that. The recommended path is the manual command, which is the most visible and reversible.

### Step 2 — dry run first (no rows written)

Run the offline JDBC dry-run from the JAR (no Forge needed) to prove the source side is wired up:

```bash
java -cp /opt/agrarius/mods/ftb-quests-mysql-sync-1.2.0.jar \
     net.agrarius.ftbquestssync.migration.MigrateDryRunMain \
     --src maria --db-host DB_HOST --db-name agrarius_test \
     --db-user agrarius --db-pass REPLACE_ME
```

Expect one line per player and a final `maria mode done: ok=<N> noSnbt=0 noUuid=0 bad=0`. If `ok` matches your player count, the source side is correct.

You can also dry-run inside the server with `/ftbsync migrate true` (the `true` = dryRun); the log shows `Migration [DRY-RUN] would upsert player=…` lines and writes nothing.

### Step 3 — import the teams (both servers)

On **agr1** console:

```text
/ftbsync importteams
```

Then on **agr2** console, the same command. Each peer writes its own FTB Teams into the DB.

### Step 4 — migrate the players (one server)

On **one** server console:

```text
/ftbsync migrate false
```

The command returns immediately; the work runs on a daemon thread. Watch `logs/latest.log`:

- `Migration source mariadb://…@… returned <N> blob(s)` — the source yielded N players
- `Migration UUID remap: name=… legacyUuid=… -> currentUuid=… claimedRewardKeys=…` — one per remapped player
- `Migration upsert: player=… teamId=… party=… bytes=… revision=… hash=…` — one per imported player
- the final **summary** line:

```text
===== MIGRATION SUMMARY ===== players=<N> migrated=<M> uuidRemapped=<R> \
uuidMissedNoUsercache=<X> rankRows=<RR> claimScopes=<CS> failed=<F>
```

> [!CAUTION]
> 🟡 **Check the summary before telling players it's done:**
> - **`uuidMissedNoUsercache` must be `0`** — otherwise those players will not see their data (see the rule at the top).
> - **`failed` must be `0`** — otherwise the marker is not written and a restart retries.
> - `rankRows` and `claimScopes` > 0 confirm ranks and reward dedup were migrated.

### Step 5 — bring up the second server

The second peer does **not** need to run `/ftbsync migrate` — the data is already in MySQL. On first player login it loads the migrated rows from the database (verified: a migrated row written by `migrator` is correctly read and pushed cross-server on login). Run `/ftbsync importteams` on it too if you haven't already.

### Step 6 — verify in MySQL

```sql
-- imported quest data
SELECT server_id, COUNT(*) FROM ftbquests_teamdata
 WHERE server_id = 'migrator' GROUP BY server_id;

-- imported ranks (per migrated solo player)
SELECT COUNT(*) FROM ftbquests_rank_progress;

-- imported reward-claim dedup rows
SELECT COUNT(*) FROM ftbquests_reward_claim_scopes;
```

## Configuration reference

All keys live in the `[migration]` block of `ftbquestssync-server.toml`, or as `-Dftbquestssync.migration.<key>=<value>` JVM properties (which override the TOML).

| TOML key | Default | Required? | Meaning |
|---|---|---|---|
| `runOnBoot` | `false` | **yes** | Master switch. `false` = migration never runs and the `/ftbsync migrate` command is rejected. |
| `remapUuids` | `true` | **yes** (if auth was switched) | Remap each export's legacy offline UUID → current online UUID via `usercache.json`. |
| `usercachePath` | `/opt/agrarius/usercache.json` | yes | Path to the vanilla usercache used for the name → UUID mapping. |
| `redisKeyPrefix` | `stratos:data:player:blob:` | yes (Redis source) | Full key prefix up to and including the last `:` before the UUID. |
| `redisDb` | `0` | no | Redis logical DB number. |
| `sourceMysqlHost` | `""` | yes (MariaDB source) | Source MariaDB host. **Empty = skip MariaDB (Redis-only).** |
| `sourceMysqlPort` | `3306` | no | |
| `sourceMysqlDatabase` | `CHANGEME` | yes (MariaDB source) | Source database name (on Agrarius = `agrarius_test`). |
| `sourceMysqlUsername` | `CHANGEME` | yes (MariaDB source) | Read-only user on the source. |
| `sourceMysqlPassword` | `""` | yes (MariaDB source) | Source user password. |
| `sourcePlayersTable` | `core_players` | no | Per-player table `(id, uuid, username, …)`. |
| `sourceDataTable` | `core_player_data` | no | Per-snapshot table `(id_player, id, data LONGBLOB, created_at, …)`. |
| `sourceUuidColumn` / `sourceIdColumn` | `uuid` / `id` | no | Columns on the per-player table. |
| `sourceIdPlayerColumn` / `sourceDataColumn` / `sourceCreatedAtColumn` | `id_player` / `data` / `created_at` | no | Columns on the per-snapshot table. |
| `serverIdTag` | `migrator` | no | Written into `ftbquests_teamdata.server_id` for imported rows (used for rollback). |
| `markerDir` | `/opt/agrarius/config` | no | Directory for the per-server marker file. Must be writable by the Forge JVM. |
| `maxPlayers` | `0` | no | Cap players per run (`0` = no cap). Use to chunk a very large import across restarts. |
| `maxBlobBytes` | `16777216` (16 MiB) | no | Hard cap on one legacy blob (zip-bomb guard). |
| `maxSnbtBytes` | `8388608` (8 MiB) | no | Hard cap on the **decompressed** `quests.snbt` entry. |
| `dryRun` | `false` | no | Parse and report but write nothing (and no marker). |

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `uuidMissedNoUsercache > 0` in the summary | Those players are not in `usercache.json` | Refresh the usercache so it contains every player, then re-run `/ftbsync migrate false`. |
| `Migration source mariadb://… returned 0 blob(s)` | Wrong table/column names, or the source is empty | `SELECT COUNT(DISTINCT id_player) FROM <db>.core_player_data;` to confirm rows exist. |
| `Migration source redis://… returned 0 blob(s)` | Wrong `redisKeyPrefix` (or Redis is genuinely empty — then MariaDB is used) | `redis-cli -n <db> --scan --pattern '*' \| head` to discover the real prefix. |
| `… failed: NOAUTH Authentication required.` | Redis needs a password | Set `redisPassword` in the `[redis]` block (the migrator reuses that pool). |
| `… failed: Access denied for user` | Wrong source DB creds or missing `SELECT` | Grant `SELECT` on the source tables; check the password in the toml. |
| `… failed: Communications link failure` | Cannot reach the source host | The mod shades `mysql-connector-j` (`jdbc:mysql://`, MariaDB-compatible); check host/port/firewall. |
| `Migration failed for player <uuid>` + stack trace | A corrupt ZIP / odd layout for that one player | That player is skipped, the rest continue, the marker is **not** written; inspect the trace. |
| `/ftbsync migrate not enabled` (chat) | `runOnBoot = false` | Set `runOnBoot = true` even if you only ever use the manual command. |
| `FTB Sync MySQL is unavailable.` | Mod still initialising | Wait a few seconds and retry. |
| `FTB Teams API is not loaded yet.` | `/ftbsync importteams` ran too early | Wait a few seconds and retry. |
| `… already completed for serverId=…` | Marker file from a previous run | Expected. To re-run, delete the marker (see Rollback). |
| After import a player's quest log is empty | Live save overwrote the import on that player's first `markDirty` with stale in-memory state | Re-run the migration with the player offline, or restart after everyone has logged in once. |

## Rollback

Imported rows are tagged `server_id = '<serverIdTag>'` (default `migrator`):

```sql
DELETE FROM ftbquests_teamdata WHERE server_id = 'migrator';
-- optional: also drop the FTB Teams rows that /ftbsync importteams wrote
DELETE FROM ftbquests_team_info       WHERE updated_by_server IN ('agr1', 'agr2');
DELETE FROM ftbquests_team_membership WHERE updated_by_server IN ('agr1', 'agr2');
```

To force a re-run, also delete the per-server marker and restart (or re-issue `/ftbsync migrate`):

```bash
rm /opt/agrarius/config/ftbquestssync.migration.done.<serverId>
```

## Standalone dry-run utilities

The shaded JAR ships two `main` entry points that need no Forge runtime and never write to a database or the marker:

| Command | Use | Output |
|---|---|---|
| `DryRunMain <dir-or-zip>` | Sanity-check a folder of `.zip` exports | `[OK <uuid>] snbt=…B gzip=…B hash=…` per zip (`[no-quests.snbt]` if the inner entry is named differently) |
| `MigrateDryRunMain --src maria …` | End-to-end check the MariaDB source parses | one line per player + `maria mode done: ok=… noSnbt=… noUuid=… bad=…` |

`MigrateDryRunMain` flags: `--src maria` (required), `--db-host`/`--db-port`/`--db-name`/`--db-user`/`--db-pass`, `--players-table`/`--data-table`, the `--*-column` overrides, and `--limit <N>`.

> [!TIP]
> If you only have an **Adminer SQL dump** (`INSERT INTO … VALUES (…,'PK\x03…',…)`) rather than live JDBC access, load the dump into a temporary MariaDB with the `core_players` + `core_player_data` schema and point the migrator at that DB. The migrator reads via JDBC `getBytes()` and never sees the SQL string escaping.

## Key files

- `Config.java` — TOML/JVM config, policy IDs, server ID fallback, `[migration]` block.
- `MySQLBackend.java` — schema, save/load, solo rank progress, reward claim guard, migration writers.
- `RedisSync.java` — Redis publish/subscribe and remote reload.
- `RankSoloProgress.java` — per-player rank/shop progress handling.
- `TeamDataMixin.java` — FTB Quests dirty/save hook.
- `RewardClaimMixin.java` — cross-server reward dedupe and repeatable shop exception.
- `TeamSync.java` / `TeamMaterializer.java` — FTB Teams DB sync and forced client full-sync after login/materialization.
- `migration/LegacyQuestMigrator.java` — the one-shot migration orchestrator.
- `migration/RankProgressMigrator.java` / `RewardClaimScopeSeeder.java` / `UuidRemapper.java` — rank, reward-dedup and UUID-remap stages.
- `migration/MysqlBlobSource.java` / `RedisBlobSource.java` / `ZipSnbtExtractor.java` — the source readers.
- `migration/MigrationCommand.java` / `ImportTeamsCommand.java` — the `/ftbsync migrate` and `/ftbsync importteams` commands.

## What to share

Share this mod project only: source code, Gradle build, wrapper, dependency libs, config example, and documentation.

Do not share production DB/Redis passwords, internal CT IDs, public/private runtime IP addresses, or unrelated backend/frontend/infra code from the monorepo root.
