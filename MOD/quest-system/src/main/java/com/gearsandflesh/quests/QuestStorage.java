package com.gearsandflesh.quests;

import com.gearsandflesh.quests.data.QuestDefinition;
import com.gearsandflesh.quests.data.QuestGoalType;
import com.gearsandflesh.quests.data.QuestReward;
import com.gearsandflesh.quests.data.QuestRewardKind;
import com.gearsandflesh.quests.data.QuestSavedData;
import com.gearsandflesh.quests.data.QuestType;
import com.gearsandflesh.quests.QuestService.OperationResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Quest list persistence as JSON so configs can travel with the modpack. */
public final class QuestStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private QuestStorage() {
    }

    /** The single well-known file: used for /quests export, /quests import, and fresh-world presets. */
    public static Path configPath() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("gearsandflesh_quests.json");
    }

    public static String exportJson(List<QuestDefinition> definitions) {
        JsonObject root = new JsonObject();
        JsonArray quests = new JsonArray();
        for (QuestDefinition definition : definitions) {
            JsonObject quest = new JsonObject();
            quest.addProperty("id", definition.id());
            JsonArray types = new JsonArray();
            definition.types().forEach(questType -> types.add(questType.name()));
            quest.add("types", types);
            quest.addProperty("title", definition.title());
            quest.addProperty("description", definition.description());
            quest.addProperty("goalType", definition.goalType().name());
            JsonArray goalIds = new JsonArray();
            definition.goalIds().forEach(goalIds::add);
            quest.add("goalIds", goalIds);
            quest.addProperty("target", definition.target());
            quest.addProperty("enabled", definition.enabled());
            JsonArray prerequisites = new JsonArray();
            definition.prerequisites().forEach(prerequisites::add);
            quest.add("prerequisites", prerequisites);
            JsonArray rewards = new JsonArray();
            for (QuestReward reward : definition.rewards()) {
                JsonObject rewardJson = new JsonObject();
                rewardJson.addProperty("kind", reward.kind().name());
                if (!reward.value().isEmpty()) {
                    rewardJson.addProperty("value", reward.value());
                }
                if (reward.kind() != QuestRewardKind.COMMAND) {
                    rewardJson.addProperty("amount", reward.amount());
                }
                rewards.add(rewardJson);
            }
            quest.add("rewards", rewards);
            quests.add(quest);
        }
        root.add("quests", quests);
        return GSON.toJson(root);
    }

    public static List<QuestDefinition> parseJson(String json) {
        List<QuestDefinition> result = new ArrayList<>();
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject() || !element.getAsJsonObject().has("quests")) {
            return result;
        }
        JsonArray quests = element.getAsJsonObject().getAsJsonArray("quests");
        for (JsonElement questElement : quests) {
            if (!questElement.isJsonObject()) {
                continue;
            }
            JsonObject quest = questElement.getAsJsonObject();
            List<QuestType> types = new ArrayList<>();
            if (quest.has("types") && quest.get("types").isJsonArray()) {
                for (JsonElement typeElement : quest.getAsJsonArray("types")) {
                    types.add(parseEnum(typeElement, QuestType.DAILY));
                }
            } else {
                types.add(parseEnum(quest.get("type"), QuestType.DAILY));
            }
            List<String> goalIds = new ArrayList<>();
            if (quest.has("goalIds") && quest.get("goalIds").isJsonArray()) {
                for (JsonElement id : quest.getAsJsonArray("goalIds")) {
                    goalIds.add(id.getAsString());
                }
            }
            List<QuestReward> rewards = new ArrayList<>();
            if (quest.has("rewards") && quest.get("rewards").isJsonArray()) {
                for (JsonElement rewardElement : quest.getAsJsonArray("rewards")) {
                    JsonObject rewardJson = rewardElement.getAsJsonObject();
                    QuestRewardKind kind = parseEnum(rewardJson.get("kind"), QuestRewardKind.MONEY);
                    String value = rewardJson.has("value") ? rewardJson.get("value").getAsString() : "";
                    int amount = rewardJson.has("amount") ? rewardJson.get("amount").getAsInt() : 1;
                    rewards.add(new QuestReward(kind, value, amount));
                }
            }
            List<String> prerequisites = new ArrayList<>();
            if (quest.has("prerequisites") && quest.get("prerequisites").isJsonArray()) {
                for (JsonElement prereq : quest.getAsJsonArray("prerequisites")) {
                    prerequisites.add(prereq.getAsString());
                }
            }
            result.add(new QuestDefinition(
                    quest.has("id") ? quest.get("id").getAsString() : "",
                    types,
                    quest.has("title") ? quest.get("title").getAsString() : "",
                    quest.has("description") ? quest.get("description").getAsString() : "",
                    parseEnum(quest.get("goalType"), QuestGoalType.MANUAL),
                    goalIds,
                    quest.has("target") ? quest.get("target").getAsInt() : 1,
                    rewards,
                    !quest.has("enabled") || quest.get("enabled").getAsBoolean(),
                    prerequisites,
                    0
            ));
        }
        return result;
    }

    public static OperationResult exportToFile(MinecraftServer server) {
        try {
            List<QuestDefinition> all = QuestSavedData.get(server).definitions(true);
            Files.writeString(configPath(), exportJson(all));
            return QuestService.OperationResult.ok("已导出 " + all.size() + " 个任务到 " + configPath());
        } catch (IOException e) {
            return QuestService.OperationResult.error("导出失败：" + e.getMessage());
        }
    }

    public static OperationResult importFromFile(MinecraftServer server) {
        Path path = configPath();
        if (!Files.exists(path)) {
            return QuestService.OperationResult.error("找不到 " + path);
        }
        try {
            List<QuestDefinition> definitions = parseJson(Files.readString(path));
            if (definitions.isEmpty()) {
                return QuestService.OperationResult.error("文件里没有任务");
            }
            QuestSavedData data = QuestSavedData.get(server);
            int saved = 0;
            List<String> errors = new ArrayList<>();
            for (QuestDefinition definition : definitions) {
                OperationResult valid = QuestService.validateDefinition(server, definition);
                if (!valid.success()) {
                    errors.add(definition.id() + "（" + valid.message() + "）");
                    continue;
                }
                if (data.upsert(definition)) {
                    saved++;
                }
            }
            String summary = "已导入 " + saved + "/" + definitions.size() + " 个任务";
            if (!errors.isEmpty()) {
                summary += "，跳过：" + String.join("、", errors);
            }
            QuestService.broadcastUpdate(server);
            return QuestService.OperationResult.ok(summary);
        } catch (Exception e) {
            return QuestService.OperationResult.error("导入失败：" + e.getMessage());
        }
    }

    /** Loads quest definitions from the config file; returns an empty map when missing or invalid. */
    public static Map<String, QuestDefinition> loadDefinitions(MinecraftServer server) {
        Path path = configPath();
        if (!Files.exists(path)) {
            return Map.of();
        }
        try {
            List<QuestDefinition> definitions = parseJson(Files.readString(path));
            Map<String, QuestDefinition> result = new LinkedHashMap<>();
            for (QuestDefinition definition : definitions) {
                if (!definition.id().isBlank()) {
                    result.put(definition.id(), definition);
                }
            }
            return result;
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Writes the current quest definitions to the config file so they travel with the modpack. */
    public static void saveDefinitions(MinecraftServer server, List<QuestDefinition> definitions) {
        try {
            Files.writeString(configPath(), exportJson(definitions));
        } catch (IOException e) {
            // Best-effort: log but don't crash the save.
            org.slf4j.LoggerFactory.getLogger(QuestStorage.class)
                    .warn("[任务] 写入任务配置失败：{}", e.getMessage());
        }
    }

    private static <E extends Enum<E>> E parseEnum(JsonElement element, E fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return Enum.valueOf(fallback.getDeclaringClass(), element.getAsString());
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }
}
