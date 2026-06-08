package net.agrarius.ftbquestssync;

import dev.ftb.mods.ftbquests.quest.BaseQuestFile;
import dev.ftb.mods.ftbquests.quest.Chapter;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisSyncMergeTest {

    private static CompoundTag tag(String key, long value) {
        CompoundTag t = new CompoundTag();
        t.putLong(key, value);
        return t;
    }

    private static CompoundTag tag(String key, String value) {
        CompoundTag t = new CompoundTag();
        t.putString(key, value);
        return t;
    }

    private static CompoundTag c() {
        return new CompoundTag();
    }

    @Test
    void started_keepsMin() {
        CompoundTag local = c();
        local.put("started", tag("abc", 200L));
        CompoundTag remote = c();
        remote.put("started", tag("abc", 100L));

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        assertEquals(100L, result.getCompound("started").getLong("abc"));

        // reverse symmetry
        CompoundTag result2 = RedisSync.mergeTeamDataNbt(remote, local, null);
        assertEquals(100L, result2.getCompound("started").getLong("abc"));
    }

    @Test
    void completed_keepsMin() {
        CompoundTag local = c();
        local.put("completed", tag("q1", 50L));
        CompoundTag remote = c();
        remote.put("completed", tag("q1", 30L));

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        assertEquals(30L, result.getCompound("completed").getLong("q1"));

        CompoundTag result2 = RedisSync.mergeTeamDataNbt(remote, local, null);
        assertEquals(30L, result2.getCompound("completed").getLong("q1"));
    }

    @Test
    void taskProgress_keepsMax() {
        CompoundTag local = c();
        local.put("task_progress", tag("t1", 5L));
        CompoundTag remote = c();
        remote.put("task_progress", tag("t1", 10L));

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        assertEquals(10L, result.getCompound("task_progress").getLong("t1"));

        CompoundTag result2 = RedisSync.mergeTeamDataNbt(remote, local, null);
        assertEquals(10L, result2.getCompound("task_progress").getLong("t1"));
    }

    @Test
    void completionCount_keepsMax() {
        CompoundTag local = c();
        local.put("completion_count", tag("c1", 3L));
        CompoundTag remote = c();
        remote.put("completion_count", tag("c1", 7L));

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        assertEquals(7L, result.getCompound("completion_count").getLong("c1"));

        CompoundTag result2 = RedisSync.mergeTeamDataNbt(remote, local, null);
        assertEquals(7L, result2.getCompound("completion_count").getLong("c1"));
    }

    @Test
    void claimedRewards_unionLocalWins() {
        CompoundTag local = c();
        CompoundTag localRewards = new CompoundTag();
        localRewards.putLong("keyA", 1L);
        local.put("claimed_rewards", localRewards);

        CompoundTag remote = c();
        CompoundTag remoteRewards = new CompoundTag();
        remoteRewards.putString("keyA", "x");
        remoteRewards.putLong("keyB", 2L);
        remote.put("claimed_rewards", remoteRewards);

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        CompoundTag mergedRewards = result.getCompound("claimed_rewards");
        assertTrue(mergedRewards.contains("keyA"));
        assertTrue(mergedRewards.contains("keyB"));
        assertEquals(1L, mergedRewards.getLong("keyA"));
    }

    @Test
    void repeatable_union() {
        CompoundTag local = c();
        CompoundTag localRep = new CompoundTag();
        localRep.putLong("old", 1L);
        local.put("repeatable", localRep);

        CompoundTag remote = c();
        CompoundTag remoteRep = new CompoundTag();
        remoteRep.putLong("new", 2L);
        remote.put("repeatable", remoteRep);

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        CompoundTag merged = result.getCompound("repeatable");
        assertEquals(1L, merged.getLong("old"));
        assertEquals(2L, merged.getLong("new"));
    }

    @Test
    void playerData_union() {
        CompoundTag local = c();
        CompoundTag localPd = new CompoundTag();
        localPd.putString("keep", "yes");
        local.put("player_data", localPd);

        CompoundTag remote = c();
        CompoundTag remotePd = new CompoundTag();
        remotePd.putString("add", "please");
        remote.put("player_data", remotePd);

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        CompoundTag merged = result.getCompound("player_data");
        assertEquals("yes", merged.getString("keep"));
        assertEquals("please", merged.getString("add"));
    }

    @Test
    void scalarName_remoteWins() {
        CompoundTag local = c();
        local.putString("name", "local");
        CompoundTag remote = c();
        remote.putString("name", "remote");

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        assertEquals("remote", result.getString("name"));
    }

    @Test
    void scalarMissingInRemote_keepsLocal() {
        CompoundTag local = c();
        local.putString("name", "myteam");
        CompoundTag remote = c();

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        assertEquals("myteam", result.getString("name"));
    }

    @Test
    void remoteOnlyTopLevelKey_copied() {
        CompoundTag local = c();
        CompoundTag remote = c();
        CompoundTag pd = new CompoundTag();
        pd.putString("some", "data");
        remote.put("player_data", pd);

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        assertEquals("data", result.getCompound("player_data").getString("some"));
    }

    @Test
    void localOnlyKey_preserved() {
        CompoundTag local = c();
        CompoundTag custom = new CompoundTag();
        custom.putString("val", " preserved");
        local.put("custom_key", custom);
        CompoundTag remote = c();

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, null);
        assertEquals(" preserved", result.getCompound("custom_key").getString("val"));
    }

    @Test
    void convergence_idempotent() {
        CompoundTag local = c();
        local.put("started", tag("a", 200L));
        local.put("task_progress", tag("a", 5L));
        CompoundTag remote = c();
        remote.put("started", tag("a", 100L));
        remote.put("task_progress", tag("a", 10L));

        CompoundTag m1 = RedisSync.mergeTeamDataNbt(local, remote, null);
        CompoundTag m2 = RedisSync.mergeTeamDataNbt(m1, remote, null);
        assertTrue(m1.equals(m2), "Merging again with same remote should be idempotent");
    }

    @Test
    void inputsNotMutated() {
        CompoundTag local = c();
        local.put("started", tag("x", 1L));
        local.putString("name", "orig");
        CompoundTag localCopy = local.copy();

        CompoundTag remote = c();
        remote.put("started", tag("x", 2L));
        remote.putString("name", "new");
        CompoundTag remoteCopy = remote.copy();

        RedisSync.mergeTeamDataNbt(local, remote, null);

        assertTrue(local.equals(localCopy), "local argument must not be mutated");
        assertTrue(remote.equals(remoteCopy), "remote argument must not be mutated");
    }

    private static final long CHAP_ID = 42L;
    private static final long QUEST_ID = 12345L;
    private Set<Long> originalTeamClaimChapterIds;

    @BeforeEach
    void saveConfig() {
        originalTeamClaimChapterIds = Config.teamClaimChapterIds;
    }

    @AfterEach
    void restoreConfig() {
        Config.teamClaimChapterIds = originalTeamClaimChapterIds;
    }

    private static BaseQuestFile mockFileWithChapter(Chapter chapter) {
        BaseQuestFile file = mock(BaseQuestFile.class);
        when(file.getAllChapters()).thenReturn(List.of(chapter));
        return file;
    }

    @Test
    void shopCycle_remoteAdvanced_adoptsRemoteState() {
        Config.teamClaimChapterIds = Set.of(CHAP_ID);

        Quest quest = new Quest(QUEST_ID, null);
        Chapter chapter = new Chapter(CHAP_ID, null, null) {
            @Override
            public List<Quest> getQuests() {
                return List.of(quest);
            }
        };
        BaseQuestFile file = mockFileWithChapter(chapter);

        String questKey = QuestObjectBase.getCodeString(QUEST_ID);

        CompoundTag local = c();
        local.put("completion_count", tag(questKey, 1L));
        local.put("started", tag(questKey, 100L));

        CompoundTag remote = c();
        remote.put("completion_count", tag(questKey, 2L));
        remote.put("started", tag(questKey, 500L));

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, file);

        assertEquals(2L, result.getCompound("completion_count").getLong(questKey),
                "remote advanced cycle => remote completion_count wins");
        assertEquals(500L, result.getCompound("started").getLong(questKey),
                "remote advanced cycle => local stale started cleared, remote 500 adopted");
    }

    @Test
    void shopCycle_localAdvanced_keepsLocalState() {
        Config.teamClaimChapterIds = Set.of(CHAP_ID);

        Quest quest = new Quest(QUEST_ID, null);
        Chapter chapter = new Chapter(CHAP_ID, null, null) {
            @Override
            public List<Quest> getQuests() {
                return List.of(quest);
            }
        };
        BaseQuestFile file = mockFileWithChapter(chapter);

        String questKey = QuestObjectBase.getCodeString(QUEST_ID);

        CompoundTag local = c();
        local.put("completion_count", tag(questKey, 3L));
        local.put("started", tag(questKey, 100L));

        CompoundTag remote = c();
        remote.put("completion_count", tag(questKey, 1L));
        remote.put("started", tag(questKey, 50L));

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, file);

        assertEquals(3L, result.getCompound("completion_count").getLong(questKey),
                "local advanced cycle => local completion_count preserved");
        assertEquals(100L, result.getCompound("started").getLong(questKey),
                "local advanced cycle => remote stale started cleared, local 100 preserved");
    }

    @Test
    void shopCycle_chapterNotClaimed_normalMergeApplies() {
        Config.teamClaimChapterIds = Set.of(999L);

        Quest quest = new Quest(QUEST_ID, null);
        Chapter chapter = new Chapter(CHAP_ID, null, null) {
            @Override
            public List<Quest> getQuests() {
                return List.of(quest);
            }
        };
        BaseQuestFile file = mockFileWithChapter(chapter);

        String questKey = QuestObjectBase.getCodeString(QUEST_ID);

        CompoundTag local = c();
        local.put("completion_count", tag(questKey, 1L));
        local.put("started", tag(questKey, 100L));

        CompoundTag remote = c();
        remote.put("completion_count", tag(questKey, 2L));
        remote.put("started", tag(questKey, 500L));

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, file);

        assertEquals(2L, result.getCompound("completion_count").getLong(questKey),
                "chapter not claimed => normal MAX merge for completion_count");
        assertEquals(100L, result.getCompound("started").getLong(questKey),
                "chapter not claimed => normal MIN merge for started");
    }

    @Test
    void shopCycle_equalCycles_normalMerge() {
        Config.teamClaimChapterIds = Set.of(CHAP_ID);

        Quest quest = new Quest(QUEST_ID, null);
        Chapter chapter = new Chapter(CHAP_ID, null, null) {
            @Override
            public List<Quest> getQuests() {
                return List.of(quest);
            }
        };
        BaseQuestFile file = mockFileWithChapter(chapter);

        String questKey = QuestObjectBase.getCodeString(QUEST_ID);

        CompoundTag local = c();
        local.put("completion_count", tag(questKey, 2L));
        local.put("started", tag(questKey, 100L));

        CompoundTag remote = c();
        remote.put("completion_count", tag(questKey, 2L));
        remote.put("started", tag(questKey, 50L));

        CompoundTag result = RedisSync.mergeTeamDataNbt(local, remote, file);

        assertEquals(2L, result.getCompound("completion_count").getLong(questKey),
                "equal cycles => normal MAX merge for completion_count");
        assertEquals(50L, result.getCompound("started").getLong(questKey),
                "equal cycles => normal MIN merge for started");
    }
}
