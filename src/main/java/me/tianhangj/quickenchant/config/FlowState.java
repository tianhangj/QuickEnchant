package me.tianhangj.quickenchant.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import me.tianhangj.quickenchant.QuickEnchant;
import fi.dy.masa.malilib.util.FileUtils;
import fi.dy.masa.malilib.util.data.json.JsonUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persisted prepare-flow state, survives game restarts: the manually selected enchantable-item
 * boxes and the per-item-id enchantment selections. The box selection is inherited when the
 * previous run did not end normally; the enchantment selections are kept as preferences.
 */
public class FlowState {
    private static final String FILE_NAME = "quick-enchant-state.json";

    public static boolean lastRunNormalEnd = true;
    public static final List<Integer> selectedBoxes = new ArrayList<>();
    public static final Map<String, StoredEnchantConfig> enchantConfigs = new LinkedHashMap<>();

    public record StoredRow(String mode, String ench, int level) {
    }

    public record StoredEnchantConfig(boolean exactly, List<StoredRow> rows) {
    }

    public static void load() {
        lastRunNormalEnd = true;
        selectedBoxes.clear();
        enchantConfigs.clear();

        var file = FileUtils.getConfigDirectory().resolve(FILE_NAME);
        JsonElement element = JsonUtils.parseJsonFile(file);
        if (element == null || !element.isJsonObject()) {
            return;
        }
        JsonObject root = element.getAsJsonObject();

        lastRunNormalEnd = root.has("lastRunNormalEnd") && root.get("lastRunNormalEnd").getAsBoolean();

        if (root.has("selectedBoxes") && root.get("selectedBoxes").isJsonArray()) {
            root.getAsJsonArray("selectedBoxes").forEach(e -> selectedBoxes.add(e.getAsInt()));
        }

        if (root.has("enchantConfigs") && root.get("enchantConfigs").isJsonObject()) {
            JsonObject configs = root.getAsJsonObject("enchantConfigs");
            for (Map.Entry<String, JsonElement> entry : configs.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject cfg = entry.getValue().getAsJsonObject();
                boolean exactly = cfg.has("exactly") && cfg.get("exactly").getAsBoolean();
                List<StoredRow> rows = new ArrayList<>();
                if (cfg.has("rows") && cfg.get("rows").isJsonArray()) {
                    cfg.getAsJsonArray("rows").forEach(r -> {
                        if (r.isJsonObject()) {
                            JsonObject row = r.getAsJsonObject();
                            rows.add(new StoredRow(
                                    row.has("mode") ? row.get("mode").getAsString() : "WITH",
                                    row.has("ench") ? row.get("ench").getAsString() : "",
                                    row.has("level") ? row.get("level").getAsInt() : 0));
                        }
                    });
                }
                enchantConfigs.put(entry.getKey(), new StoredEnchantConfig(exactly, rows));
            }
        }
    }

    public static void save() {
        try {
            var dir = FileUtils.getConfigDirectory();
            if (!java.nio.file.Files.exists(dir)) {
                FileUtils.createDirectoriesIfMissing(dir);
            }
            JsonObject root = new JsonObject();
            root.addProperty("lastRunNormalEnd", lastRunNormalEnd);

            com.google.gson.JsonArray boxes = new com.google.gson.JsonArray();
            selectedBoxes.forEach(boxes::add);
            root.add("selectedBoxes", boxes);

            JsonObject configs = new JsonObject();
            for (Map.Entry<String, StoredEnchantConfig> entry : enchantConfigs.entrySet()) {
                JsonObject cfg = new JsonObject();
                cfg.addProperty("exactly", entry.getValue().exactly());
                com.google.gson.JsonArray rows = new com.google.gson.JsonArray();
                for (StoredRow row : entry.getValue().rows()) {
                    JsonObject rowJson = new JsonObject();
                    rowJson.addProperty("mode", row.mode());
                    rowJson.addProperty("ench", row.ench());
                    rowJson.addProperty("level", row.level());
                    rows.add(rowJson);
                }
                cfg.add("rows", rows);
                configs.add(entry.getKey(), cfg);
            }
            root.add("enchantConfigs", configs);

            JsonUtils.writeJsonToFile(root, dir.resolve(FILE_NAME));
        } catch (Exception e) {
            QuickEnchant.LOGGER.error("Failed to save quick-enchant state", e);
        }
    }
}
