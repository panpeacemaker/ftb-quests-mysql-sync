package dev.ftb.mods.ftbquests.quest;

import dev.ftb.mods.ftbquests.quest.reward.Reward;
import dev.ftb.mods.ftbquests.quest.task.Task;

import java.util.Collection;
import java.util.List;

public class Quest extends QuestObject {
    public Quest(long id, Chapter chapter) {
        super(id);
    }

    public Quest() {
    }

    public Collection<Task> getTasks() {
        return List.of();
    }

    public Collection<Reward> getRewards() {
        return List.of();
    }
}
