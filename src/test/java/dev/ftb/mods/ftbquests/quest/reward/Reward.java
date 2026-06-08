package dev.ftb.mods.ftbquests.quest.reward;

import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObjectBase;

public abstract class Reward extends QuestObjectBase {
    public Reward(long id, Quest quest) {
        super(id);
    }

    public Reward() {
    }
}
