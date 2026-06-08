package dev.ftb.mods.ftbquests.quest;

public abstract class QuestObjectBase {
    public long id;

    public QuestObjectBase(long id) {
        this.id = id;
    }

    public QuestObjectBase() {
    }

    public long getId() {
        return id;
    }

    public static String getCodeString(long id) {
        return String.format("%016X", id);
    }

    public final String getCodeString() {
        return getCodeString(id);
    }

    public static long getID(QuestObjectBase obj) {
        return obj == null ? 0L : obj.id;
    }
}
