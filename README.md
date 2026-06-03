# ftb-quests-mysql-sync

Server-side Forge 1.20.1 mod for Agrarius. It keeps FTB Quests progress persistent across two backend servers (`agr1` and `agr2`), syncs canonical quest/team state through MySQL/MariaDB, uses Redis as an invalidation bus, and keeps rank/shop progress solo per player.

This repository is intentionally scoped to the mod project only. Runtime secrets, internal infrastructure identifiers, public/private IP addresses, and production port mappings are kept out of this tree.

## Current status

| Area | Status |
|---|---|
| Minecraft/Forge target | Forge 1.20.1 server-side mod |
| Build command | `./gradlew clean build` |
| Output JAR | `build/libs/ftb-quests-mysql-sync-1.0.4.jar` |
| Deployment model | same JAR on `agr1` and `agr2` |
| Canonical storage | MySQL/MariaDB |
| Live invalidation | Redis pub/sub |
| Shared quests | remain team-shared |
| Rank/shop chapters | solo per-player via policy config |
| Reward protection | DB dedupe for shared and one-shot rewards |
| Shop/rank claim fix | repeatable solo rewards bypass permanent DB reward-id dedupe; stale DB progress is injected per-player on login |
| Login delay | 5s delay before SyncTeamDataMessage to avoid client-side race conditions (freeze fix) |
| markDirty debounce | 3s cooldown between async MySQL saves to prevent server tick lag during login bursts |
| FTB Teams GUI sync | forced full team sync after login/materialization to avoid missing client team data |
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
sendDeltaPackets = false
conflictPolicy = "reload_remote"

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
build/libs/ftb-quests-mysql-sync-1.0.3.jar
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

## What to share

Share this mod project only: source code, Gradle build, wrapper, dependency libs, config example, and documentation.

Do not share production DB/Redis passwords, internal CT IDs, public/private runtime IP addresses, or unrelated backend/frontend/infra code from the monorepo root.
