# ftb-quests-mysql-sync

Server-side Forge 1.20.1 mod for Agrarius. It keeps FTB Quests progress persistent across two backend servers (`agr1` and `agr2`), syncs canonical quest/team state through MySQL/MariaDB, uses Redis as an invalidation bus, and keeps rank/shop progress solo per player.

This repository is intentionally scoped to the mod project only. Runtime secrets, internal infrastructure identifiers, public/private IP addresses, and production port mappings are kept out of this tree.

## Current status

| Area | Status |
|---|---|
| Minecraft/Forge target | Forge 1.20.1 server-side mod |
| Current version | `1.1.8` |
| Build command | `./gradlew clean reobfShadowJar` |
| Output JAR | `build/libs/ftb-quests-mysql-sync-1.1.8.jar` |
| Deployment model | same JAR on `agr1` and `agr2` |
| Canonical storage | MySQL/MariaDB |
| Live invalidation | Redis pub/sub |
| Shared quests | remain team-shared |
| Rank/shop chapters | solo per-player via policy config |
| Reward protection | DB dedupe for shared and one-shot rewards |
| FTB Teams sync | party membership, owner, live color/name across servers (`syncTeams`, default off) |
| FTB Chunks sync | claimed + force-loaded chunks per team (`syncChunks`, default off) |
| Cross-server team mgmt | pending invites (consent), presence/online-dot, owner/officer actions (1.1.8) |
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

### 1. Player changes quest progress

- FTB Quests mutates `TeamData`.
- The mod hooks `TeamData.markDirty`.
- A server-side snapshot is created.
- JDBC write runs asynchronously through the backend executor.
- After a successful write, the backend publishes a Redis update with revision/hash.

### 2. Remote backend receives Redis update

- The remote backend checks revision/hash.
- It loads canonical data from MySQL/MariaDB.
- It merges safe local/remote fields where needed.
- It sends FTB Quests refresh packets, primarily `SyncTeamDataMessage`, to affected players.

### 3. Player reconnects after crash/failover

- On login, the backend attempts a MySQL reload before relying on local in-memory state.
- The player receives the latest DB-backed quest state.
- Any progress that reached MySQL before the crash is preserved.

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

The requested fallback is implemented:

```java
System.getProperty("luckperms.server", "unknown")
```

## Configuration template

Runtime path:

```text
/opt/agrarius/config/ftbquestssync-server.toml
```

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

Output:

```text
build/libs/ftb-quests-mysql-sync-1.1.2.jar
```

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

## Tested scenarios

- `./gradlew clean build` passed.
- Same JAR deployed on both Agrarius backends.
- Both backends booted with MySQL and Redis enabled.
- Hard crash of `agr1` was tested; proxy moved/reconnected player flow to `agr2`.
- Hard crash of `agr2` was tested; proxy moved/reconnected player flow to `agr1`.
- Quest/team rows exist in the DB.
- Redis remote update was visible in logs.
- Login reload from MySQL was visible in logs.

## Failover reality

Guaranteed: quest progress that reached MySQL survives reconnect/failover and either backend can load the latest DB-backed state.

Not guaranteed: the final milliseconds before hard poweroff if async DB write did not finish, world/block sync, or perfect live modded Velocity transfer without reconnect. Forge clients can sometimes hit `unregistered packet`; reconnecting through the proxy reloads canonical quest data.

## Key files

- `Config.java` — TOML/JVM config, policy IDs, server ID fallback.
- `MySQLBackend.java` — schema, save/load, solo rank progress, reward claim guard.
- `RedisSync.java` — Redis publish/subscribe and remote reload.
- `RankSoloProgress.java` — per-player rank/shop progress handling.
- `TeamDataMixin.java` — FTB Quests dirty/save hook.
- `RewardClaimMixin.java` — cross-server reward dedupe and repeatable shop exception.
- `TeamSync.java` / `TeamMaterializer.java` — FTB Teams DB sync and forced client full-sync after login/materialization.
- `BaseQuestFileMixin.java` — access to FTB quest/team data where needed by sync logic.
- `migration/LegacyQuestMigrator.java` — one-shot import of legacy per-player quest exports.

## Legacy per-player quest-data migration

When the mod is first deployed onto a server that already has historical
quest progress stored in another system (e.g. an older Velocity-side
sync that wrote per-player ZIPs to a shared store), the
`[migration]` config block lets the mod pull that data into
`ftbquests_teamdata` automatically on the first server start so the live
sync machinery has authoritative state from day one.

### What it reads

For every player, the migrator expects a per-player ZIP archive whose
layout matches the historical format. The ZIP must contain a
`quests.snbt` entry (UTF-8 SNBT text of the FTB Quests `TeamData`
serialization for that player). Other entries in the ZIP are ignored.

Two sources are tried in order; the first non-empty result wins per
player:

1. **Redis** (preferred). Each player is read from a key whose name is
   `<migrationRedisKeyPrefix><uuid>` where `<uuid>` may use either the
   dashed (8-4-4-4-12) or undashed (32-hex) form. The key value must be
   the raw ZIP bytes. Configure `migrationRedisKeyPrefix` to match the
   prefix the legacy system writes (for example
   `stratos:`, `old-export:player:blob:`, etc. — there is no
   built-in assumption beyond the `<uuid>` suffix).
2. **Source MariaDB** (fallback). The source database is expected to
   expose a per-player table joined to a per-snapshot table:

   ```sql
   <sourcePlayersTable>(
     <sourceIdColumn>,           -- integer PK
     <sourceUuidColumn>,         -- varchar(36), canonical UUID
     username, ...
   )
   <sourceDataTable>(
     <sourceIdPlayerColumn>,     -- FK -> <sourcePlayersTable>.<sourceIdColumn>
     <sourceIdColumn>,           -- integer PK
     <sourceDataColumn>,         -- LONGBLOB, raw ZIP bytes
     <sourceCreatedAtColumn>,    -- timestamp
     <sourceBackupTypeColumn>,   -- integer
     <sourceServerColumn>        -- varchar(64), nullable
   )
   ```

   For every player the row with the latest `<sourceCreatedAtColumn>` is
   used. If a `WHERE <sourceBackupTypeColumn>=0` filter is required to
   pick only quest-export snapshots, leave the default (all rows) and
   the most recent wins; the snapshot age is the canonical signal.

### What it writes

For each player the migrator:

1. Extracts `quests.snbt` from the ZIP.
2. Parses SNBT → vanilla `CompoundTag` and normalises the
   `uuid:` field to canonical 8-4-4-4-12 form.
3. Calls the mod's own `MySQLBackend.saveTeamData(uuid, tag)` path,
   which gzip-compresses the tag, SHA-256-sums it, and UPSERTs into
   `ftbquests_teamdata` with `team_id = <player-uuid>` and
   `server_id = <migrationServerIdTag>` (default `migrator`).
4. Records a per-server marker file
   `<configDir>/ftbquestssync.migration.done.<serverId>` so that
   subsequent restarts on the same server are no-ops. The marker is
   per-`serverId` so a multi-server cluster (each with its own
   `serverId`) can run the migration on the first start of each peer
   without races or silent skip behaviour.

### Configuration

In `/opt/agrarius/config/ftbquestssync-server.toml` (or via
`-Dftbquestssync.migration.*` JVM properties):

```toml
[migration]
# CHANGEME: turn on only on the server that should perform the import
runOnBoot          = false
# CHANGEME: prefix matching the legacy system. The <uuid> suffix is
# parsed with or without dashes.
redisKeyPrefix     = "CHANGEME:player:blob:"
redisDb            = 0
# CHANGEME: when the Redis source is empty, the mod falls back to a
# source MariaDB whose credentials and table layout are configured
# below. Leave the host empty to skip the MariaDB source entirely.
sourceMysqlHost     = "CHANGEME"
sourceMysqlPort     = 3306
sourceMysqlDatabase = "CHANGEME"
sourceMysqlUsername = "CHANGEME"
sourceMysqlPassword = "CHANGEME"
# The defaults below match a per-player table layout with
#   (id, uuid, username, …) joined to
#   (id_player, id, data, created_at, backup_type, server)
sourcePlayersTable      = "core_players"
sourceDataTable         = "core_player_data"
sourceUuidColumn        = "uuid"
sourceIdColumn          = "id"
sourceIdPlayerColumn    = "id_player"
sourceDataColumn        = "data"
sourceCreatedAtColumn   = "created_at"
# Free-form tag written into ftbquests_teamdata.server_id so operators
# can tell which rows came from a migration vs. live sync.
serverIdTag             = "migrator"
# 0 = no cap (process every player the source yields).
maxPlayers              = 0
# When true, the migrator parses and reports but never writes rows
# and never writes the marker file. Use this for the first run.
dryRun                  = false
# When non-empty, the migration runs only if Config.serverId matches.
# Leave empty to allow any server to perform the import.
# CHANGEME: set this to the serverId that should run the import.
runOnServerId           = ""
```

### Recommended rollout

There are two ways to trigger a migration: an automatic one on the
first server start (`runOnBoot = true`), and an explicit operator
command (`/ftbsync migrate`). Both write through the same code path
(`LegacyQuestMigrator.runNow`) and produce the same marker file.
Pick one; the command is usually cleaner because the operator sees
the result in the chat / console right away, and because it skips
the per-`serverId` whitelist.

**Automatic path** (used when `runOnBoot = true`):

1. Build the mod and place the JAR on the first server (call it
   `<serverId-A>`) along with the regular `[mysql]` and `[redis]`
   config that points at the **target** database. Leave
   `runOnBoot = false` and `dryRun = true` for the first start.
2. With `runOnServerId = "<serverId-A>"` and `dryRun = true`, start
   the server. Watch the log for `Migration source … returned N
   blob(s)` and `Migration [DRY-RUN] would upsert player=…` for every
   player. Verify the player count and a sampling of UUIDs match what
   the legacy system holds.
3. Flip `dryRun = false` and `runOnBoot = true`, then restart the
   server. The log should end with `Migration upsert: player=…` for
   every player and `Migration marker written to …`.
4. Start the other server(s). The marker file is per-`serverId`, so
   the second server's own marker is written only when that server
   boots. If you want only one server to perform the import, set
   `runOnServerId = "<serverId-A>"` everywhere and start
   `<serverId-A>` first.

**Manual command path** (used when `runOnBoot = false` but the
operator still wants to trigger the import):

The mod exposes two operator commands; the first pulls the legacy
per-player quest data, the second pulls the currently-materialised
FTB Teams into the shared database. They are typically run as a
pair on the first server start that has both the legacy source
available and the new MySQL/Redis reachable.

1. Set `runOnBoot = true` in the config (this is what unlocks the
   commands at all; the flag is checked at command dispatch time).
   Leave `dryRun = true`.
2. Start the server as usual. In the console or in-game as op, run
   one of:

   ```text
   /ftbsync importteams                    # run on BOTH agr1 and agr2
   /ftbsync migrate                        # run on ONE server only
   /ftbsync migrate true                   # override to dryRun=true
   /ftbsync migrate false                  # override to dryRun=false
   /ftbsync migrate false 50               # dryRun=false, maxPlayers=50
   /ftbsync migrate 100                    # TOML dryRun, maxPlayers=100
   ```

   The `importteams` command walks every FTB Teams party / solo
   team that currently exists on the running server and UPSERTs it
   into `ftbquests_team_info` and `ftbquests_team_membership` with
   the real `serverId` (so rows tagged `agr1` come from running on
   agr1, rows tagged `agr2` from agr2). Run it on **both** backends
   so the database holds every server-local team.

   The `migrate` command returns immediately and runs the per-
   player quest data import on a daemon thread, so the server
   tick is not blocked. Watch `latest.log` for
   `Migration source … returned … blob(s)` and per-player
   `Migration upsert: player=…` lines. Run it on **one** server
   only — the same marker file prevents a second peer from
   re-importing on the next boot.
3. To re-run, delete the per-server marker file under `<configDir>/`
   and run the command again. To wipe imported rows, drop them from
   `ftbquests_teamdata` (the live sync will repopulate from current
   in-memory state once the players log in).

**Party handling.** Each export's `name:` field is
`<display>#<team-uuid>`; the migrator detects whether `<team-uuid>`
is the player's own UUID (solo team) or a distinct UUID (party). The
log line `Migration upsert: player=… teamId=… party=… partyName=…`
exposes the resolved team id and party name. The quest data is
UPSERTed under the resolved `teamId`; the rest of the FTB Teams
materialisation (party membership rows in `ftbquests_team_info` /
`ftbquests_team_membership`) is left to the live FTB Teams sync on
the first login of each affected player, so a stray solo import of a
party member is reconciled by the mod's existing
`TeamMaterializer` paths when the player actually connects.

### Standalone dry-run utility

For a quick "would this work against the real bytes?" check before any
of the above, the shaded JAR also ships a `DryRunMain` entrypoint that
reads every `.zip` under a directory and reports its `quests.snbt`
SHA-256 + UUID + size without touching MySQL:

    ```bash
    java -cp /path/to/ftb-quests-mysql-sync-1.2.0.jar \
         net.agrarius.ftbquestssync.migration.DryRunMain \
         /path/to/zip-dir
    ```

    The summary at the bottom distinguishes three outcomes per file:
    `[OK <uuid>]`, `[no-quests.snbt]` (the ZIP exists but has no export
    entry — typically an unrelated file that happens to use the same
    container), and `[BAD ]` (the ZIP itself failed to parse).

    For a full end-to-end check against the real source MariaDB
    (without the Forge runtime), use the JDBC variant that runs the
    same `MysqlBlobSource` query the live migrator uses, and prints
    the resolved team id, party name, gzip size, and SHA-256 per
    player:

    ```bash
    java -cp /path/to/ftb-quests-mysql-sync-1.2.0.jar \
         net.agrarius.ftbquestssync.migration.MigrateDryRunMain \
         --src maria \
         --db-host <SOURCE_HOST> --db-port 3306 --db-name <SOURCE_DB> \
         --db-user <USER> --db-pass <PASS> \
         --players-table core_players --data-table core_player_data \
         --limit 0
    ```

    Override column names with `--uuid-column`, `--id-column`,
    `--id-player-column`, `--data-column`, `--created-at-column`
    if your schema deviates from the defaults. The output prints
    one line per player and a final summary: `ok=… noSnbt=…
    noUuid=… bad=…`.

### Log lines to watch for

After the live (non-dry-run) migration, the operator should expect
the following in `logs/latest.log`, in this order, right before the
"ready" log line:

```text
Migration source redis://<host>:<port> db=<n> prefix=<prefix> returned <N> blob(s)
Legacy quest migration done: playersSeen=<N> upserts=<M> skippedNoSnbt=<K> failed=<F> elapsedMs=<T> dryRun=false
Migration marker written to <configDir>/ftbquestssync.migration.done.<serverId>
```

A second, peer-server boot adds the same three lines scoped to that
peer's `serverId` and writes a second marker file. After both peers
have booted, the rest of the runtime behaves exactly like 1.1.9.

### Scale

There is no built-in cap on the number of players migrated per run.
`maxPlayers = 0` means "no cap"; the source yields however many blobs
it has and the migrator processes them sequentially in a single pass
on the server thread. Empirically each player takes a few milliseconds
(extract ZIP → parse SNBT → write NBT → gzip → UPSERT), so 70–200+
players complete in well under a minute on a warm JDBC pool. If your
deployment expects thousands of players, set `maxPlayers` to a
positive integer, restart, and the migrator will run that many
players and write a marker; subsequent restarts continue until the
source is exhausted.

### Troubleshooting

| Symptom in `latest.log` | Likely cause | Fix |
|---|---|---|
| `Migration source redis://… returned 0 blob(s)` | `migrationRedisKeyPrefix` does not match what the legacy system writes | `redis-cli -h <host> KEYS '<prefix>*'` to see what keys exist; align the TOML prefix |
| `Migration source redis://… failed: NOAUTH Authentication required.` | The Redis source is protected by `AUTH` but no password is configured | Set `migrationRedisKeyPrefix` is unrelated — set `redisPassword` in the regular `[redis]` block (the migrator reuses that pool) |
| `Migration source redis://… failed: JedisConnectionException: …` | Wrong host/port or firewall | Verify `redis-cli -h <host> -p <port> PING`; this is a network issue, not a migration issue |
| `Migration source mariadb://… failed: Access denied for user '…'` | `migrationSourceMysqlUsername`/`Password` is wrong or the user has no `SELECT` on the source tables | Grant `SELECT` on the source DB to the configured user; check that the password matches |
| `Migration source mariadb://… returned 0 blob(s)` | `sourcePlayersTable` / `sourceDataTable` names are wrong, or the source MariaDB really is empty for that user | `SELECT COUNT(*) FROM <sourceDataTable>` directly; check that the user's default database matches `migrationSourceMysqlDatabase` |
| `Legacy quest migration done: … failed=12 …` | A handful of players had corrupt ZIPs or non-standard layouts | Inspect `Migration failed for player <uuid>: <reason>` lines just above the summary; failed players are skipped, the rest are imported, the marker is **not** written so a restart will retry the failures |
| `Migration finished with N failure(s); marker NOT written` | Same as above — the marker stays absent so the next restart retries | Fix the underlying cause or accept the failed count; deletion of `<configDir>/ftbquestssync.migration.done.<serverId>` also forces a re-run |
| `Legacy quest migration skipped: this serverId=agr2 does not match runOnServerId=agr1` | `migrationRunOnServerId` is set and the current server's `serverId` is different | Either clear `runOnServerId` so any server may run, or set it to the current server's `serverId` |
| `Legacy quest migration already completed for serverId=… (marker at …); skipping` | Marker file from a previous run is present | Expected behaviour after a successful run; delete the marker file under `<configDir>/` to force a re-run |
| `Migration source … returned 0 blob(s)` but the legacy system has data | The legacy system writes with a different prefix, **or** the legacy data lives in a different Redis DB number | Try `redis-cli -n 0..15 KEYS '*' \| head` to find the actual prefix and DB; set `migrationRedisDb` and `migrationRedisKeyPrefix` accordingly |

### Rollback

The migration is a UPSERT, so removing the imported rows is a single
SQL statement per affected player:

```sql
DELETE FROM ftbquests_teamdata
 WHERE server_id = 'migrator'
   AND team_id IN ('<uuid-1>', '<uuid-2>', ...);
```

To force a re-run after a rollback, also delete the per-server marker
file under `<configDir>/`. Subsequent live syncs from any connected
Forge backend will repopulate `ftbquests_teamdata` with the current
in-memory state on the first `TeamData.markDirty` for each affected
player, so the legacy-imported rows are not needed after the first
post-rollback login.

### SQL dump vs. JDBC

If you only have a SQL dump from the legacy system (Adminer-style
`INSERT INTO … VALUES (…,'PK\x03…',…)` lines) rather than live JDBC
access, do not use the migrator directly — the `'\…\…'` SQL string
escaping and embedded backslash sequences are not what JDBC sees.
Instead, **load the dump into a temporary MariaDB** with the same
`core_players` + `core_player_data` schema and point the migrator at
that database; the migrator reads via standard JDBC `getBytes()` and
never sees the SQL dump's quoting. The standalone `DryRunMain` can
verify ZIPs straight off the filesystem, but the migrator itself only
talks JDBC.

## What to share

Share this mod project only: source code, Gradle build, wrapper, dependency libs, config example, and documentation.

Do not share production DB/Redis passwords, internal CT IDs, public/private runtime IP addresses, or unrelated backend/frontend/infra code from the monorepo root.
