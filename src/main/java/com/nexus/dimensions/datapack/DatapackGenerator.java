package com.nexus.dimensions.datapack;

import com.nexus.dimensions.config.DimensionPreset;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Writes a minimal, spec-valid vanilla datapack for each Tier 2 preset:
 * a custom {@code dimension_type}, the {@code dimension} entry that binds
 * it to a (deliberately simple, flat) generator, and one JSON file per
 * {@code customBiomes} entry. See DESIGN.md section 6 for what this can
 * and can't do — in particular, the generator here is always
 * {@code minecraft:flat}; Nexus Dimensions' own noise terrain is a Tier 1
 * capability only.
 * <p>
 * Hand-built JSON on purpose (no Gson dependency) since the shapes here
 * are small and fixed.
 */
public final class DatapackGenerator {

    /**
     * config.yml's {@code datapackPackFormat} — must match the pack_format
     * of the exact Minecraft/Paper build this server runs, or the generated
     * datapack can silently fail to activate regardless of how many times
     * the server restarts. This used to be a hardcoded constant here (fixed
     * at 48, Minecraft 1.21/1.21.1's value) which was wrong for anything
     * newer, including the 1.21.4 this plugin otherwise targets (which
     * needs 61) — now it's a config value specifically so a version
     * mismatch is a one-line YAML edit instead of a new build. See
     * config.yml's comment for the lookup table/link and DESIGN.md
     * section 6.
     */
    private final int packFormat;

    private final Logger logger;

    public DatapackGenerator(Logger logger, int packFormat) {
        this.logger = logger;
        this.packFormat = packFormat;
    }

    /** Returns the {level-name}/datapacks directory, or null if no world is loaded yet. */
    public File resolveDatapacksDir() {
        if (Bukkit.getWorlds().isEmpty()) {
            return null;
        }
        File primaryWorldFolder = Bukkit.getWorlds().get(0).getWorldFolder();
        return new File(primaryWorldFolder, "datapacks");
    }

    public boolean writeDatapack(DimensionPreset preset) {
        File datapacksDir = resolveDatapacksDir();
        if (datapacksDir == null) {
            logger.warning("[NexusDimensions] Cannot resolve the datapacks folder yet (no world loaded).");
            return false;
        }
        String packName = "nexus_" + preset.id;
        File packDir = new File(datapacksDir, packName);
        File dataDir = new File(packDir, "data/nexus");

        try {
            Files.createDirectories(dataDir.toPath());
            write(new File(packDir, "pack.mcmeta"), packMcMeta(preset));

            File dimensionTypeDir = new File(dataDir, "dimension_type");
            Files.createDirectories(dimensionTypeDir.toPath());
            write(new File(dimensionTypeDir, preset.id + ".json"), dimensionType(preset));

            File dimensionDir = new File(dataDir, "dimension");
            Files.createDirectories(dimensionDir.toPath());
            write(new File(dimensionDir, preset.id + ".json"), dimensionEntry(preset));

            if (!preset.customBiomes.isEmpty()) {
                File biomeDir = new File(dataDir, "worldgen/biome");
                Files.createDirectories(biomeDir.toPath());
                for (DimensionPreset.CustomBiome cb : preset.customBiomes) {
                    String path = cb.id.contains(":") ? cb.id.substring(cb.id.indexOf(':') + 1) : cb.id;
                    write(new File(biomeDir, path + ".json"), biome(cb));
                }
            }

            logger.info("[NexusDimensions] Wrote/refreshed datapack for '" + preset.id
                    + "' — a server restart is required for it to take effect.");
            return true;
        } catch (IOException e) {
            logger.severe("[NexusDimensions] Failed writing datapack for '" + preset.id + "': " + e.getMessage());
            return false;
        }
    }

    private void write(File file, String content) throws IOException {
        Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
    }

    private String packMcMeta(DimensionPreset preset) {
        return """
                {
                  "pack": {
                    "pack_format": %d,
                    "description": "Nexus Dimensions - generated dimension type for %s"
                  }
                }
                """.formatted(packFormat, preset.id);
    }

    private String dimensionType(DimensionPreset preset) {
        DimensionPreset.WorldHeight wh = preset.resolvedWorldHeight();
        String fixedTime = wh.fixedTime != null ? "\n  \"fixed_time\": " + wh.fixedTime + "," : "";

        // Gameplay flags come from WorldHeight directly (safe Overworld-like
        // defaults unless a preset explicitly overrides them) rather than being
        // auto-derived from `effects` - picking the Nether's sky for its look
        // shouldn't silently also make water evaporate or beds stop working.
        String infiniburn = wh.ultrawarm ? "#minecraft:infiniburn_nether" : "#minecraft:infiniburn_overworld";

        return """
                {
                  "ultrawarm": %b,
                  "natural": %b,
                  "coordinate_scale": 1.0,
                  "has_skylight": %b,
                  "has_ceiling": %b,
                  "ambient_light": %s,%s
                  "monster_spawn_light_level": 7,
                  "monster_spawn_block_light_limit": 0,
                  "piglin_safe": %b,
                  "bed_works": %b,
                  "respawn_anchor_works": %b,
                  "has_raids": %b,
                  "logical_height": %d,
                  "min_y": %d,
                  "height": %d,
                  "infiniburn": "%s",
                  "effects": "%s"
                }
                """.formatted(wh.ultrawarm, wh.natural, wh.hasSkylight, wh.hasCeiling, wh.ambientLight, fixedTime,
                wh.piglinSafe, wh.bedWorks, wh.respawnAnchorWorks, wh.hasRaids,
                wh.height, wh.minY, wh.height, infiniburn, wh.effects);
    }

    private String dimensionEntry(DimensionPreset preset) {
        DimensionPreset.WorldHeight wh = preset.resolvedWorldHeight();
        String baseBiome = preset.biomes.entries.isEmpty()
                ? "minecraft:the_void"
                : preset.biomes.entries.get(0).id;
        String surface = preset.palette.surfaceBlock;
        String subsurface = preset.palette.subsurfaceBlock;
        String deep = preset.palette.deepBlock;
        return """
                {
                  "type": "nexus:%s",
                  "generator": {
                    "type": "minecraft:flat",
                    "settings": {
                      "layers": [
                        { "block": "%s", "height": %d },
                        { "block": "%s", "height": 1 },
                        { "block": "%s", "height": 1 }
                      ],
                      "biome": "%s"
                    }
                  }
                }
                """.formatted(preset.id, deep, Math.max(1, preset.terrain.baseHeight - wh.minY - 2),
                subsurface, surface, baseBiome);
    }

    private String biome(DimensionPreset.CustomBiome cb) {
        double skyColor = parseColor(cb.skyColor);
        double fogColor = parseColor(cb.fogColor);
        double waterColor = parseColor(cb.waterColor);
        double waterFogColor = parseColor(cb.waterFogColor);
        return """
                {
                  "temperature": %s,
                  "downfall": %s,
                  "has_precipitation": true,
                  "effects": {
                    "sky_color": %d,
                    "fog_color": %d,
                    "water_color": %d,
                    "water_fog_color": %d,
                    "mood_sound": {
                      "sound": "minecraft:ambient.cave",
                      "tick_delay": 6000,
                      "block_search_extent": 8,
                      "offset": 2.0
                    }
                  },
                  "carvers": [],
                  "features": [[], [], [], [], [], [], [], [], [], [], []],
                  "spawners": { "monster": [], "creature": [], "ambient": [], "water_ambient": [], "water_creature": [], "underground_water_creature": [], "misc": [] },
                  "spawn_costs": {}
                }
                """.formatted(cb.temperature, cb.downfall, (long) skyColor, (long) fogColor, (long) waterColor, (long) waterFogColor);
    }

    private static double parseColor(String hex) {
        if (hex == null) {
            return 0;
        }
        String cleaned = hex.trim().toLowerCase(Locale.ROOT);
        try {
            if (cleaned.startsWith("0x")) {
                return Long.parseLong(cleaned.substring(2), 16);
            }
            if (cleaned.startsWith("#")) {
                return Long.parseLong(cleaned.substring(1), 16);
            }
            return Long.parseLong(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
