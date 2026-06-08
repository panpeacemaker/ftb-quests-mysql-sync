# Distribution notes — ftb-quests-mysql-sync 1.1.2

This is the handoff document for the admin / AT / CP team. It describes exactly what is being shared, how to build it, how to deploy it, what it currently does on Agrarius, and what is intentionally not promised.

## What to send

Send/share only this project:

```text
ftb-quests-mysql-sync/
```

It contains source code, Gradle wrapper, build config, compile/runtime libraries required by the current build, config example, and documentation.

Send only the mod directory. Do not include unrelated infrastructure, backend, or frontend material that may live alongside this mod in your local workspace.

## Build

```bash
cd ftb-quests-mysql-sync
./gradlew clean build
```

Output:

```text
build/libs/ftb-quests-mysql-sync-1.1.2.jar
```

## Deployment shape

Agrarius uses two Forge backends behind Velocity:

```text
Velocity proxy
  -> agr1
  -> agr2
```

Deployment expectation:

- same JAR on `agr1` and `agr2`,
- same database and Redis endpoints in config,
- different stable `serverId` per backend,
- Velocity fallback order includes both backends.

No runtime IPs, CT IDs, or production ports are included in this handoff document.

## Configuration

Runtime file:

```text
/opt/agrarius/config/ftbquestssync-server.toml
```

Template without secrets:

```toml
[mysql]
host = "DB_HOST"
port = 3306
database = "agrarius_test"
username = "agrarius"
password = "REPLACE_ME"
useSsl = true
allowPublicKeyRetrieval = false
maxPoolSize = 5
minIdle = 2

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

For `agr2`, keep the same config and change only:

```toml
[server]
serverId = "agr2"
```

## AT/CP server ID requirement

Implemented. The mod can use:

```java
System.getProperty("luckperms.server", "unknown")
```

Supported JVM example:

```text
-Dluckperms.server=agr1
```

## What works

- Reconnect/failover persistence of FTB Quests progress through MySQL/MariaDB.
- Both directions: `agr1 -> agr2` and `agr2 -> agr1`.
- Shared quest data through FTB `TeamData`.
- Solo policy for rank/shop chapters.
- Per-player solo progress in DB.
- Reward DB dedupe for shared/team and one-shot solo rewards.
- Repeatable solo/shop reward claims bypass permanent reward-id dedupe.
- Repeatable shop task progress is kept vanilla-only and stale DB rank-progress rows are deleted.
- FTB Teams full sync is forced after login/materialization so the client receives self/team data for the My Team GUI.
- `luckperms.server` fallback for server identity.

## Admin-team requirement checklist

### Gameplay / failover requirements

| Requirement | Status |
|---|---|
| After crash/disconnect, player should see the same quest progress after connecting to either backend | Implemented for progress that reached MySQL/MariaDB |
| Main goal is reconnect/failover persistence, not perfect simultaneous live questing across two islands | Implementation matches this: canonical DB state + login reload |
| Rank must not be granted to the whole team | Implemented through solo policy and per-player DB progress |
| Shop should be solo per player | Implemented through the same solo policy |
| QoL/info chapters can also be configured as solo | Supported through chapter/quest/task policy IDs |
| Whitelist/blacklist switch and configurable lists | Implemented: `policy.mode`, `soloChapterIds`, `soloQuestIds`, `soloTaskIds` |
| Team quests on the same backend should stay shared | Preserved; only solo-policy objects are stripped from shared progress |

### Technical implementation requirements

| Requirement / note | Status |
|---|---|
| Standalone addon with own config is OK | Implemented as standalone Forge server-side mod |
| Use Redis or MySQL | Implemented: MySQL/MariaDB canonical data, Redis invalidation bus |
| Do not run slow MySQL queries on the main thread | JDBC work runs async through backend executor; snapshot creation happens server-side before async write |
| Hook `TeamData.markDirty` | Implemented in `TeamDataMixin` |
| On change, send Redis message; other servers reload team and update clients | Implemented through `RedisSync` and FTB update packets |
| Prevent reward duplication | Implemented with `ftbquests_reward_claim_scopes` scoped guard |
| Handle concurrent changes on both servers | Partially: revision/hash and merge logic; not a full DB-side transactional merge engine |
| Hook FTB Teams | Implemented and enabled with `syncTeams=true`; multi-player edge cases should still be tested |
| Send source code, Gradle build and instructions | This project contains source, Gradle wrapper, build config and docs |
| GitHub sharing is OK | Private repo sharing is appropriate; share only this subproject |

### Rank and shop policy requirements

| Requirement | Status |
|---|---|
| Two servers behind Velocity | Matches current Agrarius shape |
| Rank must be individual, not group-wide | Implemented through solo policy and per-player progress |
| Shop should be individual and should not duplicate rewards | Implemented; repeatable shop claims no longer get blocked by permanent reward-id dedupe or stale solo-progress rows |
| Bevel/shop rewards need final validation | Code fix is implemented; runtime/in-game validation is still the final check |
| Team GUI shows missing data warning | Added forced FTB Teams full sync after login/materialization; runtime/in-game validation is still required |

### Server identity / infrastructure requirements

| Requirement | Status |
|---|---|
| Send mod source code | Included |
| Server ID via `System.getProperty("luckperms.server", "unknown")` | Implemented as fallback |
| JVM arg `-Dluckperms.server=agr1` | Supported |
| Setup uses Redis between servers | Implemented for quests and teams channels |

## Known limits / not promised

- No world/block sync.
- No guarantee for the last few milliseconds before hard poweroff if async DB write did not finish.
- No guarantee of perfect live modded Velocity backend transfer without reconnect; Forge clients can hit `unregistered packet`.
- No full DB-side transactional merge for every possible simultaneous FTB Quests edit edge case.
- Team sync is implemented, but split-player/team edge cases should still be tested with multiple real players.
- Exact economy policy depends on correct final chapter/quest/task IDs in config.
