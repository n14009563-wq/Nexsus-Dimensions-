package com.nexus.dimensions.structure;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads every {@code blueprints/*.yml} file in the plugin's data folder
 * into a {@link Blueprint}, the same "log and skip, don't abort" pattern
 * as {@link com.nexus.dimensions.config.PresetLoader}.
 */
public final class BlueprintLoader {

    private final Plugin plugin;
    private final Logger logger;

    public BlueprintLoader(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public Map<String, Blueprint> loadAll() {
        Map<String, Blueprint> blueprints = new LinkedHashMap<>();
        File dir = new File(plugin.getDataFolder(), "blueprints");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null) {
            return blueprints;
        }
        for (File file : files) {
            try {
                Blueprint blueprint = parse(file);
                if (blueprint != null) {
                    blueprints.put(blueprint.name, blueprint);
                    logger.info("[NexusDimensions] Loaded blueprint '" + blueprint.name + "' ("
                            + blueprint.blocks.size() + " blocks)");
                }
            } catch (Exception e) {
                logger.severe("[NexusDimensions] Failed to parse blueprint " + file.getName() + ": " + e.getMessage());
            }
        }
        return blueprints;
    }

    private Blueprint parse(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        Blueprint blueprint = new Blueprint();
        blueprint.name = yaml.getString("name");
        if (blueprint.name == null || blueprint.name.isBlank()) {
            String fileName = file.getName();
            blueprint.name = fileName.substring(0, fileName.lastIndexOf('.'));
        }

        List<Map<?, ?>> raw = yaml.getMapList("blocks");
        List<Blueprint.BlockEntry> entries = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            Blueprint.BlockEntry entry = new Blueprint.BlockEntry();
            entry.dx = intOf(m.get("dx"), 0);
            entry.dy = intOf(m.get("dy"), 0);
            entry.dz = intOf(m.get("dz"), 0);
            Object blockObj = m.get("block");
            if (blockObj != null) {
                entry.block = blockObj.toString();
            }
            Object lootObj = m.get("loot");
            if (lootObj instanceof Boolean b) {
                entry.loot = b;
            }
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            logger.warning("[NexusDimensions] Blueprint '" + blueprint.name + "' has no blocks — check the 'blocks' list in "
                    + file.getName() + ".");
        }
        blueprint.blocks = entries;
        return blueprint;
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }
}
