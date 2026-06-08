package dev.ftb.mods.ftbquests.quest;

import java.util.List;

public abstract class BaseQuestFile extends QuestObject {
    public BaseQuestFile(long id) {
        super(id);
    }

    public BaseQuestFile() {
    }

    public abstract List<Chapter> getAllChapters();
}
