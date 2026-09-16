package com.gearsandflesh.quests.data;

import com.gearsandflesh.quests.QuestConstants;
import com.gearsandflesh.quests.QuestService;
import com.gearsandflesh.quests.QuestStorage;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuestSavedData extends SavedData {
    private static final String DATA_NAME = QuestConstants.MOD_ID + "_data";
    private final Map<String, QuestDefinition> definitions = new LinkedHashMap<>();
    private final Map<UUID, Map<String, PlayerQuestState>> playerStates = new LinkedHashMap<>();

    public QuestSavedData() {
    }

    public static QuestSavedData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                tag -> load(tag, server), () -> createWithConfig(server), DATA_NAME
        );
    }

    /** Creates a fresh instance, pulling quest definitions from the config file if it exists.
     *  An empty or missing config means no quests: nothing is auto-generated, and nothing is
     *  written back — the config file is only written when an admin actually saves quests. */
    private static QuestSavedData createWithConfig(MinecraftServer server) {
        QuestSavedData data = new QuestSavedData();
        data.definitions.clear();
        data.definitions.putAll(QuestStorage.loadDefinitions(server));
        return data;
    }

    public static QuestSavedData load(CompoundTag root, MinecraftServer server) {
        QuestSavedData data = new QuestSavedData();
        data.definitions.clear();
        // Definitions come from the config file, not from the world save.
        // An empty config is respected as "no quests" — defaults are never reinstalled.
        data.definitions.putAll(QuestStorage.loadDefinitions(server));
        ListTag players = root.getList("Players", Tag.TAG_COMPOUND);
        for (int i = 0; i < players.size(); i++) {
            CompoundTag playerTag = players.getCompound(i);
            if (!playerTag.hasUUID("Player")) {
                continue;
            }
            Map<String, PlayerQuestState> states = new LinkedHashMap<>();
            ListTag stateTags = playerTag.getList("States", Tag.TAG_COMPOUND);
            for (int j = 0; j < stateTags.size(); j++) {
                CompoundTag stateTag = stateTags.getCompound(j);
                String id = stateTag.getString("Id");
                if (!id.isBlank()) {
                    List<String> found = new ArrayList<>();
                    ListTag foundTags = stateTag.getList("Found", Tag.TAG_STRING);
                    for (int k = 0; k < foundTags.size(); k++) {
                        found.add(foundTags.getString(k));
                    }
                    states.put(id, new PlayerQuestState(
                            stateTag.getString("Period"), stateTag.getInt("Revision"),
                            stateTag.getInt("Progress"), stateTag.getBoolean("Claimed"), found
                    ));
                }
            }
            if (!states.isEmpty()) {
                data.playerStates.put(playerTag.getUUID("Player"), states);
            }
        }
        data.trimStaleStates();
        return data;
    }

    /** Drops progress for deleted quests, removed pool memberships, and expired daily/weekly periods
     *  so the save stays lean; also migrates pre-multi-group state keys ("questId" -> "questId@GROUP"). */
    private void trimStaleStates() {
        playerStates.replaceAll((playerId, states) -> {
            Map<String, PlayerQuestState> migrated = new LinkedHashMap<>();
            states.forEach((key, state) -> {
                String migratedKey = key;
                if (key.indexOf('@') < 0) {
                    QuestDefinition definition = definitions.get(key);
                    if (definition != null) {
                        migratedKey = QuestService.stateKey(key, QuestService.fastestGroup(definition));
                    }
                }
                migrated.put(migratedKey, state);
            });
            return migrated;
        });
        playerStates.replaceAll((playerId, states) -> {
            states.entrySet().removeIf(stateEntry -> {
                int at = stateEntry.getKey().indexOf('@');
                if (at <= 0) {
                    return true;
                }
                QuestDefinition definition = definitions.get(stateEntry.getKey().substring(0, at));
                if (definition == null) {
                    return true;
                }
                QuestType group;
                try {
                    group = QuestType.valueOf(stateEntry.getKey().substring(at + 1));
                } catch (IllegalArgumentException ignored) {
                    return true;
                }
                if (!definition.types().contains(group)) {
                    return true; // quest no longer belongs to that pool
                }
                if (group == QuestType.SPECIAL) {
                    return false;
                }
                return !stateEntry.getValue().period().equals(QuestService.periodFor(group));
            });
            return states;
        });
        playerStates.entrySet().removeIf(entry -> entry.getValue().isEmpty());
    }

    @Override
    public CompoundTag save(CompoundTag root) {
        // Only player progress goes into the world save; definitions live in the config file.
        ListTag players = new ListTag();
        playerStates.forEach((playerId, states) -> {
            if (states.isEmpty()) return;
            CompoundTag playerTag = new CompoundTag();
            playerTag.putUUID("Player", playerId);
            ListTag stateTags = new ListTag();
            states.forEach((id, state) -> {
                CompoundTag stateTag = new CompoundTag();
                stateTag.putString("Id", id);
                stateTag.putString("Period", state.period());
                stateTag.putInt("Revision", state.revision());
                stateTag.putInt("Progress", state.progress());
                stateTag.putBoolean("Claimed", state.claimed());
                ListTag foundTags = new ListTag();
                state.found().forEach(foundId -> foundTags.add(net.minecraft.nbt.StringTag.valueOf(foundId)));
                stateTag.put("Found", foundTags);
                stateTags.add(stateTag);
            });
            playerTag.put("States", stateTags);
            players.add(playerTag);
        });
        root.put("Players", players);
        return root;
    }

    public List<QuestDefinition> definitions(boolean includeDisabled) {
        List<QuestDefinition> result = new ArrayList<>();
        for (QuestDefinition definition : definitions.values()) {
            if (includeDisabled || definition.enabled()) result.add(definition);
        }
        return Collections.unmodifiableList(result);
    }

    public QuestDefinition definition(String id) {
        return definitions.get(id);
    }

    public boolean upsert(QuestDefinition definition) {
        if (definition == null || definition.id().isBlank()) return false;
        if (!definitions.containsKey(definition.id()) && definitions.size() >= QuestConstants.MAX_QUESTS) {
            return false;
        }
        QuestDefinition existing = definitions.get(definition.id());
        QuestDefinition toStore = definition;
        if (existing != null) {
            // Bumping the revision resets everyone's progress; only gameplay content should do that.
            toStore = definition.gameplayChangedFrom(existing)
                    ? definition.withRevision(existing.revision() + 1)
                    : definition.withRevision(existing.revision());
        }
        definitions.put(toStore.id(), toStore);
        setDirty();
        return true;
    }

    public boolean remove(String id) {
        if (definitions.remove(id) == null) return false;
        // Stripping the deleted id from remaining quests keeps prerequisites from dangling.
        definitions.replaceAll((key, definition) ->
                definition.prerequisites().contains(id) ? definition.withoutPrereq(id) : definition);
        setDirty();
        return true;
    }

    public PlayerQuestState state(UUID playerId, String questId, String period, int revision) {
        Map<String, PlayerQuestState> states = playerStates.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>());
        PlayerQuestState current = states.get(questId);
        if (current == null || !current.period().equals(period) || current.revision() != revision) {
            current = new PlayerQuestState(period, revision, 0, false, List.of());
            states.put(questId, current);
            setDirty();
        }
        return current;
    }

    public void update(UUID playerId, String questId, PlayerQuestState state) {
        playerStates.computeIfAbsent(playerId, ignored -> new LinkedHashMap<>()).put(questId, state);
        setDirty();
    }

    public record PlayerQuestState(String period, int revision, int progress, boolean claimed, List<String> found) {
        public PlayerQuestState {
            period = period == null ? "" : period;
            revision = Math.max(0, revision);
            progress = Math.max(0, progress);
            found = found == null ? List.of() : List.copyOf(found);
        }
    }
}
