package dev.ftb.mods.ftbquests.quest.task;

import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObject;

public abstract class Task extends QuestObject {
    public Task(long id, Quest quest) {
        super(id);
    }

    public Task() {
    }
}
