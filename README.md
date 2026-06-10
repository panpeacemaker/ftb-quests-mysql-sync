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
| Cross-server team mgmt | pending invites (consent), presence/online-dot, owner/officer actions (1.2.0) |
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

A one-shot importer that pulls historical per-player quest progress
from a legacy store (per-player ZIPs) into the mod's own
`ftbquests_teamdata` / `ftbquests_team_info` / `ftbquests_team_membership` /
`ftbquests_rank_progress` / `ftbquests_reward_claim_scopes`
tables. Run this **once** when first deploying the mod onto a server
that already has quest progress stored elsewhere. After the import
completes, the rest of the mod's live sync machinery takes over from
the database as the authoritative source.

> [!WARNING]
> **`usercache.json` MUST contain every player before you migrate.**
>
> The legacy data is keyed by each player's *old offline* UUID, but the live
> server uses the player's *current online* UUID. The migrator bridges the two
> by matching the player **name** in `/opt/agrarius/usercache.json`. Any player
> **not present** in that file is **not remapped** — their quest data, ranks and
> reward claims are written under the stale offline UUID and **that player will
> not see any of it** until they reappear in the usercache and the migration is
> re-run for them.
>
> Before migrating: make sure `usercache.json` on the migrating server holds an
> entry for **all** players you want to migrate. After migrating, check the
> summary line in the log:
> `===== MIGRATION SUMMARY ===== players=… uuidMissedNoUsercache=0 …`.
> **`uuidMissedNoUsercache` must be `0`.** If it is non-zero, the log lists each
> missed player by name — refresh the usercache and re-run the migration.

> [!IMPORTANT]
> **What the migration now transfers (verified end-to-end):**
> - Quest progress (started / completed / task progress) → `ftbquests_teamdata`
> - Per-player **solo / rank / QoL** progress → `ftbquests_rank_progress`
>   (the runtime strips this from the team blob, so it must be migrated separately)
> - **Reward claims** → `ftbquests_reward_claim_scopes` (cross-server dedup, so an
>   already-claimed reward cannot be claimed again after migration)
> - Team-reward claim keys are normalised to `NIL_UUID` so the **client GUI** shows
>   migrated rewards as already claimed instead of offering the claim button.

### At a glance

| Question | Answer |
|---|---|
| **What does it import?** | Per-player FTB Quests `TeamData` NBT (the same data the mod's live sync writes to `ftbquests_teamdata` on every `TeamData.markDirty`). |
| **Where does the source data come from?** | Two production wire formats, tried in order: (1) Redis keys `<prefix><uuid>` (raw ZIP bytes), (2) a source MariaDB whose `core_player_data.data` column holds the same ZIP bytes. The first source that returns a non-empty result wins. (The standalone dry-run utility can also read a directory of `.zip` files for offline verification, but the live migrator never reads a filesystem.) |
| **What about FTB Teams and chunks?** | Two operator commands: `/ftbsync importteams` walks the FTB Teams currently materialised on the running server and writes them to `ftbquests_team_info` / `ftbquests_team_membership`. |
| **When does it run?** | Either automatically on the first server start (`runOnBoot = true` + marker not present), or manually via `/ftbsync migrate` (operator command). |
| **Will it run again on next boot?** | No. A per-server marker file (`<markerDir>/ftbquestssync.migration.done.<serverId>`) prevents re-runs. Delete the marker to force a re-run. |
| **Which snapshot per player?** | The newest `created_at`, breaking ties on the highest `id`. The tie-break makes the choice deterministic when two snapshots share a timestamp (otherwise the picked row would be JDBC-order-dependent). |
| **Solo vs party?** | A solo export's `uuid` field equals the source player uuid, so it imports under `team_id = <player-uuid>`. A party export carries the shared party uuid in its `uuid` field (distinct from the source player uuid); it imports once under `team_id = <party-uuid>` and other members of the same party are de-duplicated. Party `team_info`/`membership` rows are **not** written — FTB Teams materializes them on first login. (The party uuid is not in `usercache.json`, so it is not remapped; this is expected.) |
| **How long does it take?** | A few ms per player. 70–200+ players complete in under a minute. |

### Data flow

```text
                           Source side                           Target side
   ┌───────────────────────────────┐         ┌────────────────────────────────┐
   │  Redis: <prefix><uuid>        │──┐      │  MariaDB `ftbquests_teamdata`  │
   │  (raw per-player ZIP bytes)   │  │      │  ┌─────────┐                  │
   └───────────────────────────────┘  │  ┌──▶ │  │ team_id │ = <player-uuid> │
                                    ├──▶│    │  │ data    │ = gzip(NBT)      │
   ┌───────────────────────────────┐  │  │    │  │ data_h. │ = SHA-256        │
   │  MariaDB source:              │  │  │    │  │ revis.  │ = 1              │
   │  core_players                 │──┘  │    │  │ server  │ = "migrator"     │
   │  core_player_data             │     │    │  └─────────┘                  │
   │  (newest row per id_player)   │     │    │                                │
   └───────────────────────────────┘     │    │  `ftbquests_team_info`         │
                                        │    │  `ftbquests_team_membership`   │
                                        │    │  (written by /ftbsync importteams) │
                                        │    │                                │
                                        │    │  Marker file (one per serverId):│
                                        │    │  <markerDir>/                  │
                                        │    │  ftbquestssync.migration.      │
                                        │    │  done.<serverId>                │
                                        └────┴───────────────────────────────┘
```

### Prerequisites

Before triggering the migration, the operator must have:

| # | What | Why | How to verify |
|---|---|---|---|
| 1 | A working **target** MariaDB reachable from the Forge server (the one already used by the mod for live sync) | All imported rows go there | Connect to the mod's existing `[mysql]` host/port/database and run `SHOW TABLES;` — expect `ftbquests_teamdata`, `ftbquests_team_info`, `ftbquests_team_membership`, … |
| 2 | A working **Redis** reachable from the Forge server (the same one the mod uses) | The Redis source is tried first | `redis-cli -h <host> -p <port> PING` should reply `PONG`; `redis-cli -h <host> -a <pass> AUTH <pass>` if the instance is protected |
| 3 | The **legacy per-player data** present in Redis or in the source MariaDB | The migrator reads from there | For Redis: `redis-cli -h <host> KEYS '<your-prefix>*' \| wc -l`; for MariaDB: `SELECT COUNT(DISTINCT id_player) FROM core_player_data` |
| 4 | Permission to **read** the source side and **write** to the target side | The migrator is read-only on the source, write-only on the target | Source: `SELECT` on the relevant tables; target: the mod's existing `[mysql]` user already has full DML |
| 5 | The mod JAR (1.2.0 or later) on **both** server peers | `ServerStartedEvent` fires on every boot | `./gradlew build` then copy `build/libs/ftb-quests-mysql-sync-1.2.0.jar` to `/opt/agrarius/mods/` |
| 6 | **`usercache.json` on the migrating server contains an entry for every player to migrate** ⚠️ | The migrator remaps each player's legacy offline UUID to their current online UUID **by name** via this file. Players missing here keep the stale offline UUID and **will not see their migrated data**. | `python3 -c "import json;print(len(json.load(open('/opt/agrarius/usercache.json'))))"` — compare against the player count from row 3. After migrating, confirm `uuidMissedNoUsercache=0` in the summary log line. |

### Configuration reference

All keys live in the `[migration]` block of
`/opt/agrarius/config/ftbquestssync-server.toml` (or via
`-Dftbquestssync.migration.<key>=<value>` JVM properties, which
override the TOML). Every key with `CHANGEME` in the default
**must be set** before the migration will produce useful results.

| TOML key | JVM property | Default | Required? | Meaning |
|---|---|---|---|---|
| `runOnBoot` | `ftbquestssync.migration.runOnBoot` | `false` | yes (effectively) | Master switch. If `false`, the migration never auto-runs on boot and the `/ftbsync migrate` command is also rejected. Set to `true` to enable either flow. |
| `runOnServerId` | `ftbquestssync.migration.runOnServerId` | `""` | no | If non-empty, the **auto** path runs only on the server whose `serverId` matches. The manual command path ignores this. |
| `redisKeyPrefix` | `ftbquestssync.migration.redisKeyPrefix` | `"stratos:"` | yes (Redis source only) | The key prefix the legacy system writes. The migrator reads `<prefix><uuid>`. Common values: `stratos:`, `legacy:player:blob:`, `old-export:`. |
| `redisDb` | `ftbquestssync.migration.redisDb` | `0` | no | Redis logical DB number. |
| `sourceMysqlHost` | `ftbquestssync.migration.sourceMysqlHost` | `""` | yes (MariaDB source only) | Hostname of the source MariaDB. Leave empty to **skip** the MariaDB source entirely (Redis-only mode). |
| `sourceMysqlPort` | `ftbquestssync.migration.sourceMysqlPort` | `3306` | no | TCP port. |
| `sourceMysqlDatabase` | `ftbquestssync.migration.sourceMysqlDatabase` | `"CHANGEME"` | yes (MariaDB source only) | Database name on the source server. |
| `sourceMysqlUsername` | `ftbquestssync.migration.sourceMysqlUsername` | `"CHANGEME"` | yes (MariaDB source only) | Read-only user on the source. |
| `sourceMysqlPassword` | `ftbquestssync.migration.sourceMysqlPassword` | `""` | yes (MariaDB source only) | Source user password. |
| `sourcePlayersTable` | `ftbquestssync.migration.sourcePlayersTable` | `"core_players"` | no | Per-player table name. Schema: `(<idCol>, <uuidCol>, username, …)`. |
| `sourceDataTable` | `ftbquestssync.migration.sourceDataTable` | `"core_player_data"` | no | Per-snapshot table name. Schema: `(<idPlayerCol>, <idCol>, <dataCol> LONGBLOB, <createdAtCol> timestamp, …)`. |
| `sourceIdColumn` | `ftbquestssync.migration.sourceIdColumn` | `"id"` | no | Integer PK column. |
| `sourceUuidColumn` | `ftbquestssync.migration.sourceUuidColumn` | `"uuid"` | no | UUID column on the per-player table. |
| `sourceIdPlayerColumn` | `ftbquestssync.migration.sourceIdPlayerColumn` | `"id_player"` | no | FK column on the per-snapshot table. |
| `sourceDataColumn` | `ftbquestssync.migration.sourceDataColumn` | `"data"` | no | LONGBLOB column on the per-snapshot table. |
| `sourceCreatedAtColumn` | `ftbquestssync.migration.sourceCreatedAtColumn` | `"created_at"` | no | Timestamp column. The newest row per player (by this column) wins. |
| `markerDir` | `ftbquestssync.migration.markerDir` | `"/opt/agrarius/config"` | no | Parent directory for the per-server marker file. Override when the writable runtime state should not share a filesystem with the toml config. |
| `serverIdTag` | `ftbquestssync.migration.serverIdTag` | `"migrator"` | no | Free-form string written into `ftbquests_teamdata.server_id` for imported rows. Distinguishes imports from live sync writes. |
| `maxPlayers` | `ftbquestssync.migration.maxPlayers` | `0` | no | Hard cap on players per run. `0` = no cap. Use this on very large datasets to chunk the import across restarts. |
| `maxBlobBytes` | `ftbquestssync.migration.maxBlobBytes` | `16777216` (16 MiB) | no | Hard cap on a single legacy blob (Redis value or MariaDB `data` column). Raise if a real player export exceeds the cap; lower to harden against malformed sources. |
| `maxSnbtBytes` | `ftbquestssync.migration.maxSnbtBytes` | `8388608` (8 MiB) | no | Hard cap on the **decompressed** `quests.snbt` entry inside the zip. Hardens against zip bombs. |
| `dryRun` | `ftbquestssync.migration.dryRun` | `false` | no | When `true`, parse and report but never write rows and never write the marker. **Always** set this on the first run. |

### Deployment: step-by-step

The recommended path is the **manual command** — it is the most
visible (operator sees the result in chat or console right away) and
the most reversible. The auto-boot path is for fully unattended
deployments.

#### Step 0 — verify the prerequisites

```bash
# Check target MariaDB is reachable and has FTB tables
mysql -h <TARGET_HOST> -u <USER> -p \
  -e "USE <TARGET_DB>; SHOW TABLES LIKE 'ftbquests_%';"

# Check Redis is reachable
redis-cli -h <REDIS_HOST> -p <REDIS_PORT> PING

# Check the legacy data is there
redis-cli -h <REDIS_HOST> -n <DB> KEYS '<your-prefix>*' | wc -l
# or
mysql -h <SOURCE_HOST> -u <USER> -p -e \
  "SELECT COUNT(DISTINCT id_player) FROM <DB>.core_player_data;"

# Check usercache.json covers every player (count must be >= player count above)
python3 -c "import json;print('usercache entries:', len(json.load(open('/opt/agrarius/usercache.json'))))"
```

If any of these fails, fix it first; the migration is the last step.
**In particular, do not migrate until `usercache.json` covers every player**
(see the warning at the top of this section).

#### Step 1 — write the toml

Edit `/opt/agrarius/config/ftbquestssync-server.toml` and add
(or fill in) the `[migration]` block:

```toml
[migration]
runOnBoot              = false           # enable in step 3 only
redisKeyPrefix         = "stratos:"       # the legacy system's actual prefix
redisDb                = 0
sourceMysqlHost        = "CHANGEME"       # set to the source MariaDB host
sourceMysqlPort        = 3306
sourceMysqlDatabase    = "CHANGEME"
sourceMysqlUsername    = "CHANGEME"
sourceMysqlPassword    = "CHANGEME"
markerDir              = "/opt/agrarius/config"
serverIdTag            = "migrator"
maxPlayers             = 0
maxBlobBytes           = 16777216         # 16 MiB
maxSnbtBytes           = 8388608          # 8 MiB
dryRun                 = true            # ALWAYS true for the first run
```

Override `sourcePlayersTable`, `sourceDataTable`, column names,
etc. only if your source schema deviates from the defaults.

#### Step 2 — standalone dry-run (no Forge, no writes)

Before touching the live server, run the JDBC dry-run against the
source side:

```bash
java -cp /opt/agrarius/mods/ftb-quests-mysql-sync-1.2.0.jar \
     net.agrarius.ftbquestssync.migration.MigrateDryRunMain \
     --src maria \
     --db-host <SOURCE_HOST> --db-port 3306 --db-name <SOURCE_DB> \
     --db-user <USER> --db-pass <PASS> \
     --players-table core_players --data-table core_player_data
```

The expected output is one line per player and a final
`maria mode done: ok=<N> noSnbt=0 noUuid=0 bad=0`. If `ok` is
close to the number of players in the legacy system, the source
side is wired correctly.

#### Step 3 — deploy + dry-run on the live server

1. Copy the 1.2.0 JAR to `/opt/agrarius/mods/` on **both** servers.
2. Restart **only one** server first (e.g. agr1). `dryRun = true`
   means no rows are written; this is a safe verification step.
3. In the server console, run:
   ```text
   /ftbsync importteams
   /ftbsync migrate true
   ```
   The second command is equivalent to `dryRun = true`; the `true`
   argument is an override so the operator can be explicit. Watch
   the log for `Migration [DRY-RUN] would upsert player=…` lines.
4. Verify the row count in the dry-run log matches the expected
   number of players. If anything looks off, fix the config and
   restart — the dry-run is idempotent.

#### Step 4 — real import on the live server

1. In the running server console, run:
   ```text
   /ftbsync importteams
   /ftbsync migrate false
   ```
   The `false` overrides `dryRun = true` for this single command.
2. The command returns immediately; the actual work runs on a
   daemon thread so the server tick is not blocked.
3. Watch `logs/latest.log` for the per-player lines:
   - `Migration source redis://… returned <N> blob(s)` (one per
     source; Redis first, then MariaDB if Redis was empty)
   - `Migration upsert: player=<uuid> teamId=<teamId> party=<bool>
     partyName=<name> bytes=<N> revision=1 hash=<sha>`
     — one per imported player
4. The final summary lines:
   `Legacy quest migration done: playersSeen=<N> upserts=<M>
   remappedUuids=<R> remapMissed=<X> soloBindings=<B> rankRows=<RR>
   claimScopes=<CS> skippedNoSnbt=<K> failed=<F> … dryRun=false`
   followed by
   `===== MIGRATION SUMMARY ===== players=<N> migrated=<M>
   uuidRemapped=<R> uuidMissedNoUsercache=<X> rankRows=<RR>
   claimScopes=<CS> failed=<F>`
   - `migrated` should equal the number of real players.
   - **`uuidMissedNoUsercache` must be `0`** — if non-zero, those players
     were not in `usercache.json`; the log names each one. Refresh the
     usercache and re-run for them.
   - `rankRows` / `claimScopes` > 0 confirm ranks and reward-claim dedup
     were migrated.
   - `failed` should be `0`. If non-zero, the marker is **not**
     written and a restart will retry the failures.

#### Step 5 — bring up the second server

1. Restart the second server (agr2). Its `[migration]` block can
   have the same values, but it does not need to run the migrate
   command (the data is already in MySQL from step 4).
2. The live FTB Teams sync on agr2 will pick up the party/solo
   teams from the database on first player login.
3. (Optional) On agr2, run `/ftbsync importteams` to make sure its
   local FTB Teams state is also reflected in the database. This is
   only necessary if agr2 and agr1 hosted **separate** FTB Teams
   before the import.

#### Step 6 — verify in MySQL

```sql
-- imported quest data
SELECT server_id, COUNT(*) AS rows_imported, MIN(revision) AS min_rev, MAX(revision) AS max_rev
  FROM ftbquests_teamdata
 WHERE server_id = 'migrator'
 GROUP BY server_id;
-- expect: 1 row, server_id='migrator', rows_imported = <expected count>,
--         min_rev=1, max_rev=1

-- imported FTB teams
SELECT type, COUNT(*) FROM ftbquests_team_info
 WHERE updated_by_server IN ('<your-serverId-1>', '<your-serverId-2>')
 GROUP BY type;
-- expect: at least one PARTY or PLAYER row per active team

-- imported memberships
SELECT rank, COUNT(*) FROM ftbquests_team_membership
 WHERE updated_by_server IN ('<your-serverId-1>', '<your-serverId-2>')
 GROUP BY rank;
-- expect: one row per member
```

#### Step 7 — clean up the auto-run flag

The first server (agr1) wrote a per-server marker file:
`/opt/agrarius/config/ftbquestssync.migration.done.agr1`. If you
leave `runOnBoot = true` in the toml, agr1 will skip the migration
on every future boot. If you want the auto-run enabled, do nothing.
If you prefer to keep the manual command as the only path, set
`runOnBoot = false` (or remove the `[migration]` block entirely —
the commands will be rejected as `not enabled`).

### Log lines reference

The log output in `logs/latest.log` follows a fixed pattern. Use
this as the primary debugging tool.

| Log line | When it appears | What it means |
|---|---|---|
| `Migration source redis://… returned <N> blob(s)` | After successful Redis scan | The Redis source yielded N player blobs. The first non-empty source wins. |
| `Migration source redis://… failed: <reason>` | Redis unreachable / wrong prefix / AUTH failure | The Redis source failed; the mod falls back to the MariaDB source. |
| `Migration source mariadb://<user>@<host> returned <N> blob(s)` | After successful MariaDB scan | The MariaDB source yielded N player blobs. |
| `Migration source mariadb://<user>@<host> failed: <reason>` | MariaDB unreachable / wrong creds / wrong table | The MariaDB source failed; no further sources. |
| `Migration [DRY-RUN] would upsert player=… teamId=… party=… partyName=… bytes=…` | Per player, dry-run only | Would have written this row. Verifies SNBT parsing and party detection. |
| `Migration upsert: player=… teamId=… party=… partyName=… bytes=… revision=… hash=… serverIdTag=…` | Per player, live | Wrote one row to `ftbquests_teamdata` with `server_id = serverIdTag`. |
| `Migration failed for player <uuid>` followed by a Java stack trace | Per player, on exception | That player was skipped. Look at the trace to identify the cause. |
| `Legacy quest migration done: playersSeen=… upserts=… skippedNoSnbt=… failed=… elapsedMs=… dryRun=…` | Always, end of run | Final summary. `failed=0` for a clean run. |
| `Migration marker written to <markerDir>/ftbquestssync.migration.done.<serverId>` | Live run, no failures, at least one upsert | The per-server marker is now in place; subsequent restarts on this server are no-ops. |
| `Migration finished with N failure(s); marker NOT written so a restart will retry` | Live run, any failure | The marker is **not** written; the next restart will retry. |
| `Legacy quest migration already completed for serverId=… (marker at …); skipping` | Boot, marker present | The mod is in steady state; nothing to do. |
| `Legacy quest migration skipped: this serverId=… does not match runOnServerId=…` | Boot, `runOnServerId` whitelist mismatch | The other server is expected to run the import; this server is just a peer. |
| `Legacy quest migration already in progress; ignoring duplicate run` | Manual command invoked twice in quick succession | The `RUNNING` guard rejected the second invocation. |

### Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `Migration source redis://… returned 0 blob(s)` | The configured `redisKeyPrefix` does not match what the legacy system actually writes | `redis-cli -h <host> -n <db> KEYS '*' \| head -50` to discover the actual prefix; align `redisKeyPrefix` to match. |
| `Migration source redis://… failed: NOAUTH Authentication required.` | Redis requires `AUTH` but no password is set | The migrator reuses the main `[redis]` pool. Set `redisPassword` in the `[redis]` block; `migrationRedisKeyPrefix` is unrelated to authentication. |
| `Migration source redis://… failed: JedisConnectionException: …` | Wrong host/port, or a firewall blocks the connection | `redis-cli -h <host> -p <port> PING` from the same network path the Forge server uses. |
| `Migration source mariadb://<user>@<host> failed: Access denied for user '…'` | Wrong username / password, or the user has no `SELECT` on the source tables | `GRANT SELECT ON <db>.* TO '<user>'@'%' IDENTIFIED BY '<pass>';`; verify the password in the toml. |
| `Migration source mariadb://<user>@<host> failed: Communications link failure` | MySQL Connector/J cannot reach the host (or the URL uses an unsupported prefix) | The mod shades `mysql-connector-j:8.3.0` which handles `jdbc:mysql://` URLs. The `MysqlBlobSource` builds that prefix; if your source requires the `org.mariadb.jdbc` driver instead, you must add it as a runtime dependency. |
| `Migration source mariadb://<user>@<host> returned 0 blob(s)` | `sourcePlayersTable` / `sourceDataTable` / column names are wrong, or the source is genuinely empty | `mysql -h <host> -e "SELECT COUNT(*) FROM <db>.<sourceDataTable>"` to confirm the source has rows. |
| `Migration failed for player <uuid>` followed by a stack trace | A handful of players had corrupt ZIPs or non-standard layouts | The bad player is skipped, the rest of the import continues, the marker is **not** written, and a restart retries the failures. Inspect the trace to find the offending row. |
| `Legacy quest migration done: … failed=N …` (with N > 0) | A non-zero failure count | Same as above; the marker is not written, restart will retry. |
| `Legacy quest migration skipped: this serverId=agr2 does not match runOnServerId=agr1` | `runOnServerId` is set and this peer's `serverId` does not match | Either clear `runOnServerId` so any peer may run, or set it to this peer's `serverId`. |
| `Legacy quest migration already completed for serverId=…` | Marker file from a previous run is present | Expected behaviour after a successful run. To re-run, delete the marker file. |
| `Legacy quest migration already in progress; ignoring duplicate run` | The operator invoked `/ftbsync migrate` twice in quick succession | The `RUNNING` guard rejected the duplicate; wait for the first to finish. |
| `/ftbsync migrate not enabled` (chat message) | `runOnBoot = false` in the toml | The commands are gated on this flag. Set `runOnBoot = true` even if you only want manual invocations. |
| `FTB Sync MySQL is unavailable.` (chat message) | The mod's target MariaDB is not yet connected | The mod is still initialising; wait a few seconds and try again. |
| `FTB Teams API is not loaded yet.` (chat message) | `/ftbsync importteams` was run before FTB Teams finished loading | Wait a few seconds; the importteams command is also re-checked at execution time. |
| `Could not write migration marker <path>: …` | The marker file's parent directory does not exist or is not writable | Set `markerDir` to a directory the Forge JVM can `createDirectories()` into. The default `/opt/agrarius/config` requires write access. |
| After import, players log in but their quest log is empty | The live sync may have re-overwritten the import on the very first `TeamData.markDirty` of a player, with stale in-memory state | Re-run the migration with the player logged in, or restart the mod after all players have logged in once. (This is a race only on the first `markDirty` of each player.) |

### Rollback

Imported rows are tagged with `server_id = '<serverIdTag>'` (default
`'migrator'`). To wipe them in one statement:

```sql
DELETE FROM ftbquests_teamdata
 WHERE server_id = 'migrator';

-- Optional: also drop the FTB Teams rows that /ftbsync importteams wrote
DELETE FROM ftbquests_team_info
 WHERE updated_by_server IN ('agr1', 'agr2');   -- whatever the runOnServerId was

DELETE FROM ftbquests_team_membership
 WHERE updated_by_server IN ('agr1', 'agr2');
```

To force a re-run, also delete the per-server marker file:

```bash
rm /opt/agrarius/config/ftbquestssync.migration.done.<serverId>
```

Then restart the Forge server (or invoke `/ftbsync migrate` again).

### Standalone dry-run utilities

The shaded JAR ships two `main` entry points that are useful **before**
the live server is touched. They do not need a Forge runtime, do
not write to any database, and do not change the marker file.

| Command | When to use | Output |
|---|---|---|
| `DryRunMain <dir-or-zip>` | Quick sanity check on a directory of `.zip` files (e.g. the contents of a legacy backup tarball). The expected entry name inside the zip is `quests.snbt`; a `[no-quests.snbt]` outcome means the zip exists but uses a different inner entry name. | One line per zip: `[OK <uuid>] snbt=…B gzip=…B hash=…` or `[no-quests.snbt]` or `[BAD ]` |
| `MigrateDryRunMain --src maria …` | End-to-end check that the MariaDB source side is wired correctly and the data parses | One line per player: `player=… uuid=… teamId=… party=… name=… snbt=…B gzip=…B hash=…` plus a final `maria mode done: ok=… noSnbt=… noUuid=… bad=…` |

`MigrateDryRunMain` accepts these flags:

```text
--src maria                                  (required for JDBC mode)
--db-host <HOST>                              (default 127.0.0.1)
--db-port <PORT>                              (default 3306)
--db-name <DB>                                (default CHANGEME)
--db-user <USER>                              (default empty)
--db-pass <PASS>                              (default empty)
--players-table <T>                           (default core_players)
--data-table <T>                              (default core_player_data)
--uuid-column <C>                             (default uuid)
--id-column <C>                              (default id)
--id-player-column <C>                        (default id_player)
--data-column <C>                             (default data)
--created-at-column <C>                       (default created_at)
--limit <N>                                  (default 0 = no cap)
```

### Security

| Surface | Who sees it | Notes |
|---|---|---|
| `migrationServerIdTag` value (default `migrator`) | DBAs, anyone with `SELECT` on the target DB | The `server_id` column is visible to anyone querying the mod's tables. Pick a non-sensitive value. |
| Source-side credentials (`sourceMysqlPassword`, `[redis] redisPassword`) | The mod JVM only | The mod never logs credentials. The standalone dry-run utilities take `--db-pass` as a CLI argument, which is visible in shell history; consider using a read-only MySQL user. |
| `ftbquestssync.migration.markerDir` | The mod JVM | The marker file is world-readable by default; do not store secrets in the path or filename. |
| `describe()` log output | Operators reading `latest.log` | Includes the source DB username (e.g. `mariadb://<user>@<host>`) but not the password. Don't reuse your privileged DB account for the source. |
| Player UUIDs in `ftbquests_teamdata.team_id` | DBAs, anyone with `SELECT` on the target DB | These are public in-game identifiers; no special handling required. |

### What to share

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
