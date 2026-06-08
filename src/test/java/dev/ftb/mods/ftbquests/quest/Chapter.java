package dev.ftb.mods.ftbquests.quest;

import java.util.List;

public class Chapter extends QuestObject {
    public Chapter(long id, BaseQuestFile file, Object group) {
        super(id);
    }

    public Chapter() {
    }

    public List<Quest> getQuests() {
        return List.of();
    }
}
