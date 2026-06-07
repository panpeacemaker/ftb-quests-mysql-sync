# README_BOT — ftb-quests-mysql-sync

Maintenance guide for an AI coding agent. Dense, factual. Read **Hard Invariants** before any edit.

## Overview

- Forge **1.20.1** mod. Syncs across multiple MC servers via shared **MySQL + Redis**:
  - **FTB Quests TEAM data** (`syncQuests=true`): quest progress, completions, reward claims, shop cycles, completion toasts.
  - **FTB Teams** (`syncTeams=false`): party membership, owner, **team color** (live, no relog), team name.
  - **FTB Chunks** (`syncChunks=false`): claimed + force-loaded chunks per team.
- **Server-only.** Clients run **vanilla FTB** (no client mod).
- Mod id package: `net.agrarius.ftbquestssync`.
- `syncTeams` / `syncChunks` default OFF; enable in config. Both require stable per-player UUIDs from the proxy.
- **State authority = MySQL. Redis = invalidation/pub-sub only.** Peers re-read DB on every event.

## Repo Map

| Path | Responsibility |
|---|---|
| `build.gradle` | Forge + shadow plugin; version string; `shadowJar` relocates shaded deps; build task is `reobfShadowJar`. |
| `src/main/resources/ftbquestssync.mixins.json` | Mixin registry. package `net.agrarius.ftbquestssync.mixin`, `JAVA_17`, refmap. |
| `src/main/java/net/agrarius/ftbquestssync/FTBQuestsSync.java` | Mod entry; version log lines; login handler. |
| `.../Config.java` | All config. chapter-ID `Set<Long>` fields + toml parse via `longSetProp`; reads `<server>/config/ftbquestssync-server.toml`; localhost backend warning. |
| `.../MySQLBackend.java` | HikariCP singleton; SQL strings; table creation; idempotent advisory-locked migrations (`migrateRankProgressPk`, `migrateRewardClaimScopesCyclePk`); reward-claim guard `tryClaimRewardScoped(...)` → `ScopedClaimResult{firstClaim, cycleComplete}`; off-thread executor. |
| `.../RedisSync.java` | Jedis pub/sub; `publishTeamUpdate` / `applyRemoteUpdate` / `forceReloadAndPushTo`; `mergeTeamDataNbt` (field-level union); `applyShopCycleMergePolicy` + `hasAdvancedShopCycle` (shop, gated on `teamClaimChapterIds`); sends `SyncTeamDataMessage` / `ObjectCompletedMessage` / `DisplayCompletionToastMessage` / `UpdateTaskProgressMessage`. |
| `.../ShopRepeatableSync.java` | NON-mixin helper. `rewardIds(Quest)`, `allRewardsClaimed`, `resetIfCycleComplete`, `resetAllCompleteShopQuests`; uses `TeamDataAccessor`. |
| `.../ShopCycleReset.java` | NON-mixin data holder (`quest`, `playerUuid`, `now`, `cycle`) for the pending shop reset. |
| `.../RankSoloProgress.java` | Rank/solo per-player progress handling. |
| `.../mixin/RewardMixin.java` | `@Mixin(Reward)`. `@Shadow Tristate team`; forces `team=Tristate.TRUE` at `readData` RETURN for chapters in `teamClaimChapterIds` OR `teamSharedChapterIds`; `isTeamReward()` RETURN override → true for those, false for solo (rank/QoL). |
| `.../mixin/QuestMixin.java` | `@Mixin(Quest)`. `@Shadow Tristate canRepeat`; forces `canRepeat=TRUE` for `teamClaimChapterIds` (shop) ONLY; `canBeRepeated()` override. |
| `.../mixin/RewardClaimMixin.java` | `@Mixin(TeamData)`. `claimReward` HEAD guard (TEAM dedup via `MySQLBackend.tryClaimRewardScopedAsync` with `cycle=completionCount`, `questRewardIds`); pass-through for plain vanilla chapters; `onResetReward`; `forceSaveAfterClaim`; `ThreadLocal<ShopCycleReset> pendingShopReset` (set only when `teamClaimOverride && cycleComplete`). References `ShopCycleReset` and `ShopRepeatableSync` (both NON-mixin package). |
| `.../mixin/TeamDataAccessor.java` | `@Mixin(TeamData)` `@Accessor` interface for `claimedRewards`, `completionCount`, `questRepeatableTime`. |
| `.../mixin/BaseQuestFileMixin.java`, `RequestTeamDataMessageMixin.java`, `RankSoloTeamDataMixin.java`, `TeamDataMixin.java` | Pre-existing mixins. |
| `.../TeamSync.java` | FTB Teams sync. Event listeners (CREATED/DELETED/JOINED/LEFT/CHANGED/OWNERSHIP/LOGGED_IN/PROPERTIES_CHANGED); persist team_info+membership; Redis reasons `member_add/member_kick/owner_transfer/props/changed`; `applyRemoteProps` (live color/name); peer member handlers; `onPlayerChanged` uses `getPreviousTeam()` to discriminate real party-exit vs login auto-create. Publish-after-commit via `upsertMembershipFuture`. |
| `.../TeamMaterializer.java` | Reflection bridge for FTB Teams: `createTeamWithUuid`, `addPlayerToTeamReflective`, `removePlayerFromTeamReflective`, `forcePlayerToDbTeam` (stale-party detach + restore solo), `markTeamDirtyAndSync` (syncToAll), `transferPartyOwnership` (native + reflective fallback), `applyColor`, `ensureTeamMaterialized`. Login materialize converges player to DB team; solo-DB detaches from stale party. |
| `.../TeamMutationGuard.java` | Anti-echo. ThreadLocal reentrant `suppressTeam(uuid)` / `suppressPlayer(uuid)` + `isTeamSuppressed` / `isPlayerSuppressed`. Wrap peer FTB mutations so they don't re-publish. Only valid INSIDE `server.execute` (NOT across async DB futures). |
| `.../FtbSyncTeamCommand.java` | `/ftbsync team invite|kick|transfer <name>`. Owner-or-op gate. `invite` = direct add. `applyMemberKickLocal` = negative-edge (remove from kicked team by intent, not racy DB read). UUID resolver: profile cache → DB `selectPlayerUuidByName` → Mojang API (never offline UUID). **Cross-server invite/online-kick currently BROKEN — see Known Bugs.** |
| `.../ChunkSync.java`, `ChunkMaterializer.java`, `ChunkClaimRecord.java`, `ChunkApplyGuard.java`, `ChunkLimitPatcher.java`, `ChunkSeeder.java` | FTB Chunks sync: capture claim/unclaim/forceload events → DB → Redis; materialize per-team claims on login + level-load; `ChunkApplyGuard` suppression + state machine; `ChunkSeeder` one-time DB seed from canonical server; `ChunkLimitPatcher` bumps claim limits reflectively. |

## Behavior Model

### `teamClaimChapterIds` — shop (default `{3CEC7F7BAD54E4C6}`)
- Team-shared **+ repeatable**.
- Reward dedup keyed `(scope_type=TEAM, scope_uuid=teamId, reward_id, cycle=completionCount)`.
- Cycle rows are **NEVER** deleted on repeat reset (ledger prevents stale cross-server re-grant).
- `resetIfCycleComplete` handles the distributed-last-reward case.

### `teamSharedChapterIds` — progression (default = 16 chapter IDs)
- Team-shared, **claim-once, NON-repeatable** (subset of shop logic: no `canRepeat` forcing, no cycle force-reset).
- `cycle` is normally `0`.

### `soloChapterIds` — `{3622ED01311E6763 rank, 67F6F5055518AC4F QoL}`
- Per-player.

### Completion toast
- `applyRemoteUpdate` diffs newly-completed quests vs before-merge and sends `DisplayCompletionToastMessage` to online members.
- Guards: `localBeforeMerge != null && newlyCompleted.size() <= 5` (anti-spam).
- Same-server toasts come from stock FTB.

### Vanilla-client crux
- Clients key team rewards by `Util.NIL_UUID`.
- A reward is a team reward to the client ONLY if the serialized `team` Tristate is true — `Reward.writeNetData` writes the **field**, NOT a server-only `isTeamReward()` override.
- Therefore `RewardMixin` forces the **field** at load time.

## Config Keys

- File: `<server>/config/ftbquestssync-server.toml`.
- Chapter-policy keys: `policy.teamSharedChapterIds`, `policy.teamClaimChapterIds` (parsed via `longSetProp`).
- DB creds come from toml / `-D` props. Localhost backend triggers a warning.

## Build

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew clean reobfShadowJar
```

- **JDK 17 required** (newer JDK breaks Gradle 8.4).
- Output: `build/libs/ftb-quests-mysql-sync-<version>.jar`.
- MUST be `reobfShadowJar`, **NOT** `shadowJar` (plain `shadowJar` ships a deobfuscated jar that fails on the server).

## Deploy

- Servers: two or more backend instances behind a proxy (each runs the mod with a distinct `serverId`).
- Deploy the mod jar to each backend's `mods/` directory and restart.
- Start the server however your environment normally launches it.

- Put **exactly one** mod jar in `mods/`.
- Verify log:
  - `FTB Quests Sync <ver> ready (mysqlAvailable=true, redisEnabled=true, teamsRedisEnabled=true)`
  - `Done`
- No `IllegalClassLoadError` / `Mixin` error / `AbstractMethodError`.

## Quest Config vs Player Progress (do NOT confuse)

- **Quest definitions** live in `<server>/config/ftbquests/quests/` (`data.snbt`, `chapter_groups.snbt`, `chapters/*.snbt`). Deploying a new pack = back up `quests/`, stop server, replace dir, start, verify log `Loaded N chapter groups, M chapters, ... quests`.
- **Player/team progress** lives OUTSIDE config: `world/ftbquests/<teamUuid>.snbt` + MySQL `ftbquests_teamdata`. Replacing the config dir does **not** touch these.
- Completion is keyed by **quest UUID**. Stable UUIDs across a pack update ⇒ progress persists; only newly-added/changed quests (new UUIDs) appear incomplete; removed quests drop their records. This is normal content drift, **not** a reset.
- A **full progress reset** happens ONLY if someone wipes `world/ftbquests/` or truncates `ftbquests_teamdata` / `ftbquests_reward_claim_scopes`. Mod-jar or quest-config deploys do not do this.
- **Chapter-ID coupling:** if a new pack changes the 16-hex chapter IDs used in `teamClaimChapterIds` / `teamSharedChapterIds` / `soloChapterIds`, update `ftbquestssync-server.toml` (or the `Config.java` defaults) to match, else team sync silently stops applying to those chapters.

## Hard Invariants (MUST respect)

1. **NEVER** place a non-mixin helper/data class inside `net.agrarius.ftbquestssync.mixin` — Mixin throws `IllegalClassLoadError` at load (crashed a build once: `RewardClaimMixin$ShopCycleReset`). Data/helper classes go in `net.agrarius.ftbquestssync`.
2. Build ONLY with `reobfShadowJar` (reobf output). Plain `shadowJar` ships a deobfuscated jar that fails on the server.
3. **NEVER** delete cycle rows on a repeat reset (they are the cross-server stale-grant guard).
4. To make a reward genuinely team-shared for VANILLA clients you MUST force the `team` Tristate **field** (load-time), not just `isTeamReward()`.
5. Schema migrations MUST stay idempotent + advisory-locked.
6. **Never** commit secrets. DB creds come from the toml / `-D` props. Never expose DB creds or private IPs in code, logs, or docs.
7. Don't change solo (rank/QoL) behavior when editing team logic.
8. Always bump `build.gradle` version + the `FTBQuestsSync.java` version log lines **together**.
9. Live shared production DB — schema changes are real migrations; test idempotency.

## Common Tasks

### Add a chapter to a policy set
- Add the 16-hex chapter id (as `0x...L`) to the relevant `Set` in `Config.java` default, OR
- Set the corresponding toml key (`policy.teamSharedChapterIds` / `policy.teamClaimChapterIds`) on the server.
- Rebuild only needed if changing the code default.

### Change behavior
- Team-shared / repeatable logic → mixins + `RedisSync` + `ShopRepeatableSync`. Keep invariants 3, 4, 7.

### Bump version
- Edit `build.gradle` version string AND the `FTBQuestsSync.java` version log lines together (invariant 8).

## DB Schema & Migrations

- Table `ftbquests_reward_claim_scopes` PK `(scope_type, scope_uuid, reward_id, cycle)`.
- Migrations: `migrateRankProgressPk`, `migrateRewardClaimScopesCyclePk` — idempotent + advisory-locked (only first server applies).
- Tables auto-created.

## Verification Checklist

- [ ] Built with `reobfShadowJar` (not `shadowJar`).
- [ ] Exactly one jar in `mods/`.
- [ ] Log: `FTB Quests Sync <ver> ready (mysqlAvailable=true, redisEnabled=true, teamsRedisEnabled=true)`.
- [ ] Log: `Done`.
- [ ] No `IllegalClassLoadError` / Mixin error / `AbstractMethodError`.
- [ ] No non-mixin class in `.mixin` package.
- [ ] No cycle-row deletion introduced.
- [ ] `build.gradle` version == `FTBQuestsSync.java` version log.
- [ ] No secrets / private IPs in diff.

## Known Bugs (as of 1.1.8)

- 1.1.8 reworked cross-server team management. The legacy 1.0.32 direct-invite/online-kick race is superseded by **consent-based pending invites** (`ftbquests_team_invites`, accept via `/ftbteams party join`). #20 (blank names), #21 (presence/online-dot), #22 (owner/officer actions cross-server) were addressed and validated in-game (testers confirmed #20 names + #22 owner-actions; #21 presence added via `PresenceSync` heartbeat + Redis TTL).
- **Deferred / still open**:
  - #10 shop-cycle canonical-DB edge (config currently correct: single canonical server). Not a live exploit; `repeatableSoloChapterIds` is empty on both backends.
  - #16 QoL automation — backlog, not started.
- Working: party membership sync at login, team color/name live sync, chunk sync, quest sync, pending invites, presence, owner/officer actions.
- Hard dependency: stable per-player UUIDs from the proxy (Velocity modern forwarding). Offline/cracked UUID breaks all per-player sync.

## Version History

- `1.0.20` repeatable team-shared shop.
- `1.0.21` one-reward-per-team progression chapters.
- `1.0.22` cross-server completion toast.
- `1.0.26` FTB Teams party membership sync.
- `1.0.28` FTB Chunks claim + force-load sync.
- `1.0.29` team color live sync.
- `1.0.30` `/ftbsync team invite/kick/transfer` commands.
- `1.0.31` offline-kick membership convergence fix (getPreviousTeam discriminator).
- `1.0.32` publish-after-commit + negative-edge kick + UUID resolver — cross-server invite/online-kick STILL broken.
- `1.0.33` issues #1–#4: fail-closed rank claim, disband/kick migrates quests+reward scopes to solo team, single-owner invariant, claim-color live propagation.
- `1.1.1` logout force-save crash fix (bridge iface out of mixin pkg, safe cast); Redis reset events `forceReplace=true` replace local quest data instead of merge.
- `1.1.2` maintenance: stale version string fix, `.gitignore` restore.
- `1.1.3` cross-server #8/#15/#17; player-name backfill.
- `1.1.4` root-cause #7/#10/#15 (trailing flush, leave-confirm, team dedup); split-brain mitigation; chunk-bonus command (#16).
- `1.1.8` consent-based cross-server **pending team invites** (`ftbquests_team_invites`, accept via `/ftbteams party join`); #20 blank-name fix (GameProfileCache prime from `ftbquests_player_names`); #21 presence/online-dot (`PresenceSync` + `PlayerTeamPresenceMixin`, Redis TTL heartbeat); #22 owner/officer actions cross-server (GUI + command mixins, `TeamMutationGuard`).
- Companion: `web/` read-only quest viewer + admin reset console (`/api/agrarius/*`, WOT auth, CSRF-guarded reset).
