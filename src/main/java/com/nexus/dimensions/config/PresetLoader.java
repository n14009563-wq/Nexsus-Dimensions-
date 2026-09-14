package com.nexus.dimensions.config;

import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Reads every {@code presets/*.yml} file in the plugin's data folder into
 * a {@link DimensionPreset}. A malformed preset is logged and skipped
 * rather than aborting the whole load, so one bad file can't take every
 * dimension down.
 */
public final class PresetLoader {

    private final Plugin plugin;
    private final Logger logger;

    public PresetLoader(Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public Map<String, DimensionPreset> loadAll() {
        Map<String, DimensionPreset> presets = new LinkedHashMap<>();
        File presetsDir = new File(plugin.getDataFolder(), "presets");
        if (!presetsDir.exists()) {
            presetsDir.mkdirs();
        }

        File[] files = presetsDir.listFiles((dir, name) -> name.endsWith(".yml") || name.endsWith(".yaml"));
        if (files == null || files.length == 0) {
            logger.warning("[NexusDimensions] No presets found in " + presetsDir.getPath()
                    + " — drop a .yml file in there, or run /nexusdim reload after adding one.");
            return presets;
        }

        for (File file : files) {
            try {
                DimensionPreset preset = parse(file);
                if (preset != null) {
                    presets.put(preset.id, preset);
                    logger.info("[NexusDimensions] Loaded preset '" + preset.id + "' ("
                            + (preset.isTier2() ? "Tier 2 - datapack" : "Tier 1 - instant") + ")");
                }
            } catch (Exception e) {
                logger.severe("[NexusDimensions] Failed to parse preset " + file.getName() + ": " + e.getMessage());
            }
        }
        return presets;
    }

    private DimensionPreset parse(File file) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        DimensionPreset preset = new DimensionPreset();

        preset.id = yaml.getString("id");
        if (preset.id == null || preset.id.isBlank()) {
            // fall back to filename without extension
            String name = file.getName();
            preset.id = name.substring(0, name.lastIndexOf('.'));
            logger.warning("[NexusDimensions] " + file.getName() + " has no 'id' field, using filename: " + preset.id);
        }
        preset.displayName = yaml.getString("displayName", preset.id);

        String envStr = yaml.getString("environment", "NORMAL");
        try {
            preset.environment = World.Environment.valueOf(envStr.toUpperCase());
        } catch (IllegalArgumentException ex) {
            logger.warning("[NexusDimensions] Preset '" + preset.id + "': unknown environment '" + envStr
                    + "', defaulting to NORMAL");
            preset.environment = World.Environment.NORMAL;
        }

        if (yaml.contains("seed")) {
            preset.seed = yaml.getLong("seed");
        }

        if (yaml.isConfigurationSection("worldHeight")) {
            ConfigurationSection s = yaml.getConfigurationSection("worldHeight");
            DimensionPreset.WorldHeight wh = new DimensionPreset.WorldHeight();
            wh.minY = s.getInt("minY", wh.minY);
            wh.height = s.getInt("height", wh.height);
            wh.hasCeiling = s.getBoolean("hasCeiling", wh.hasCeiling);
            wh.hasSkylight = s.getBoolean("hasSkylight", wh.hasSkylight);
            wh.ambientLight = s.getDouble("ambientLight", wh.ambientLight);
            wh.effects = s.getString("effects", wh.effects);
            if (s.contains("fixedTime")) {
                wh.fixedTime = s.getLong("fixedTime");
            }
            wh.ultrawarm = s.getBoolean("ultrawarm", wh.ultrawarm);
            wh.natural = s.getBoolean("natural", wh.natural);
            wh.piglinSafe = s.getBoolean("piglinSafe", wh.piglinSafe);
            wh.bedWorks = s.getBoolean("bedWorks", wh.bedWorks);
            wh.respawnAnchorWorks = s.getBoolean("respawnAnchorWorks", wh.respawnAnchorWorks);
            wh.hasRaids = s.getBoolean("hasRaids", wh.hasRaids);
            if (wh.height % 16 != 0 || wh.minY % 16 != 0) {
                logger.warning("[NexusDimensions] Preset '" + preset.id
                        + "': worldHeight.minY and height should be multiples of 16 (vanilla dimension-type rule).");
            }
            if (wh.minY + wh.height > 2032) {
                logger.warning("[NexusDimensions] Preset '" + preset.id
                        + "': minY + height exceeds vanilla's 2032 registry cap; the datapack may fail to load.");
            }
            preset.worldHeight = wh;
        }

        if (yaml.isConfigurationSection("sky")) {
            ConfigurationSection s = yaml.getConfigurationSection("sky");
            DimensionPreset.Sky sky = new DimensionPreset.Sky();
            sky.effects = s.getString("effects", sky.effects);
            if (!sky.effects.equals("minecraft:overworld") && !sky.effects.equals("minecraft:the_nether")
                    && !sky.effects.equals("minecraft:the_end")) {
                logger.warning("[NexusDimensions] Preset '" + preset.id + "': sky.effects '" + sky.effects
                        + "' isn't one of vanilla's three valid values (minecraft:overworld / minecraft:the_nether "
                        + "/ minecraft:the_end) — the client will likely reject or ignore it.");
            }
            if (s.contains("fixedTime")) {
                sky.fixedTime = s.getLong("fixedTime");
            }
            preset.sky = sky;
        }

        ConfigurationSection terrainSec = yaml.getConfigurationSection("terrain");
        if (terrainSec != null) {
            DimensionPreset.Terrain t = preset.terrain;
            t.mode = terrainSec.getString("mode", t.mode);
            if (!t.mode.equalsIgnoreCase("heightmap") && !t.mode.equalsIgnoreCase("density3d")) {
                logger.warning("[NexusDimensions] Preset '" + preset.id + "': terrain.mode '" + t.mode
                        + "' isn't 'heightmap' or 'density3d', defaulting to heightmap.");
                t.mode = "heightmap";
            }
            t.seaLevel = terrainSec.getInt("seaLevel", t.seaLevel);
            t.baseHeight = terrainSec.getInt("baseHeight", t.baseHeight);
            t.heightVariation = terrainSec.getInt("heightVariation", t.heightVariation);

            ConfigurationSection density3dSec = terrainSec.getConfigurationSection("density3d");
            if (density3dSec != null) {
                DimensionPreset.Density3D d = t.density3d;
                d.threshold = density3dSec.getDouble("threshold", d.threshold);
                d.verticalFalloff = density3dSec.getDouble("verticalFalloff", d.verticalFalloff);
                d.shape = density3dSec.getString("shape", d.shape);
                if (!d.shape.equalsIgnoreCase("bands") && !d.shape.equalsIgnoreCase("spires")) {
                    logger.warning("[NexusDimensions] Preset '" + preset.id + "': terrain.density3d.shape '" + d.shape
                            + "' isn't 'bands' or 'spires', defaulting to bands.");
                    d.shape = "bands";
                }
                d.spireFrequency = density3dSec.getDouble("spireFrequency", d.spireFrequency);
                d.spireJitter = density3dSec.getDouble("spireJitter", d.spireJitter);
                d.spireCoreFraction = density3dSec.getDouble("spireCoreFraction", d.spireCoreFraction);
                d.spireStrength = density3dSec.getDouble("spireStrength", d.spireStrength);
                d.liquids = density3dSec.getBoolean("liquids", d.liquids);
                List<Map<?, ?>> rawBands = density3dSec.getMapList("bands");
                if (!rawBands.isEmpty()) {
                    List<DimensionPreset.Band> bands = new ArrayList<>();
                    for (Map<?, ?> raw : rawBands) {
                        DimensionPreset.Band band = new DimensionPreset.Band();
                        Object centerObj = raw.get("center");
                        Object thicknessObj = raw.get("thickness");
                        if (centerObj instanceof Number number) {
                            band.center = number.intValue();
                        }
                        if (thicknessObj instanceof Number number) {
                            band.thickness = number.intValue();
                        }
                        bands.add(band);
                    }
                    d.bands = bands;
                }
            }
            if (t.isDensity3D() && (t.craters.enabled)) {
                logger.warning("[NexusDimensions] Preset '" + preset.id
                        + "': terrain.craters is a heightmap-only concept and is ignored in density3d mode "
                        + "(overhangs/holes come from the 3D noise itself - tune terrain.noise/density3d instead).");
            }

            ConfigurationSection noiseSec = terrainSec.getConfigurationSection("noise");
            if (noiseSec != null) {
                DimensionPreset.Noise n = t.noise;
                n.frequency = noiseSec.getDouble("frequency", n.frequency);
                n.octaves = noiseSec.getInt("octaves", n.octaves);
                n.lacunarity = noiseSec.getDouble("lacunarity", n.lacunarity);
                n.gain = noiseSec.getDouble("gain", n.gain);
                n.ridged = noiseSec.getBoolean("ridged", n.ridged);
                n.warp = noiseSec.getDouble("warp", n.warp);
            }

            ConfigurationSection craterSec = terrainSec.getConfigurationSection("craters");
            if (craterSec != null) {
                DimensionPreset.Craters c = t.craters;
                c.enabled = craterSec.getBoolean("enabled", c.enabled);
                c.frequency = craterSec.getDouble("frequency", c.frequency);
                c.depth = craterSec.getInt("depth", c.depth);
                c.rimHeight = craterSec.getInt("rimHeight", c.rimHeight);
                c.jitter = craterSec.getDouble("jitter", c.jitter);
            }

            ConfigurationSection caveSec = terrainSec.getConfigurationSection("caves");
            if (caveSec != null) {
                DimensionPreset.Caves c = t.caves;
                c.enabled = caveSec.getBoolean("enabled", c.enabled);
                c.frequency = caveSec.getDouble("frequency", c.frequency);
                c.threshold = caveSec.getDouble("threshold", c.threshold);
                c.mode = caveSec.getString("mode", c.mode);
                if (!c.mode.equalsIgnoreCase("noise") && !c.mode.equalsIgnoreCase("cellular")) {
                    logger.warning("[NexusDimensions] Preset '" + preset.id + "': caves.mode '" + c.mode
                            + "' isn't 'noise' or 'cellular', defaulting to noise.");
                    c.mode = "noise";
                }
                c.cellularThreshold = caveSec.getDouble("cellularThreshold", c.cellularThreshold);
                c.cellularJitter = caveSec.getDouble("cellularJitter", c.cellularJitter);
            }
            if (t.isDensity3D() && t.caves.enabled) {
                logger.warning("[NexusDimensions] Preset '" + preset.id
                        + "': terrain.caves is a heightmap-only concept and is ignored in density3d mode "
                        + "(caves/overhangs come from the 3D noise itself there).");
            }
        }

        ConfigurationSection paletteSec = yaml.getConfigurationSection("palette");
        if (paletteSec != null) {
            DimensionPreset.Palette p = preset.palette;
            p.surfaceBlock = paletteSec.getString("surfaceBlock", p.surfaceBlock);
            p.subsurfaceBlock = paletteSec.getString("subsurfaceBlock", p.subsurfaceBlock);
            p.subsurfaceDepth = paletteSec.getInt("subsurfaceDepth", p.subsurfaceDepth);
            p.deepBlock = paletteSec.getString("deepBlock", p.deepBlock);
            p.liquidBlock = paletteSec.getString("liquidBlock", p.liquidBlock);
            p.liquidLevel = paletteSec.getInt("liquidLevel", p.liquidLevel);

            List<Map<?, ?>> rawVariants = paletteSec.getMapList("variants");
            if (!rawVariants.isEmpty()) {
                List<DimensionPreset.PaletteVariant> variants = new ArrayList<>();
                for (Map<?, ?> raw : rawVariants) {
                    DimensionPreset.PaletteVariant v = new DimensionPreset.PaletteVariant();
                    v.name = str(raw.get("name"), v.name);
                    Object surfaceObj = raw.get("surfaceBlock");
                    v.surfaceBlock = surfaceObj != null ? surfaceObj.toString() : null;
                    Object subsurfaceObj = raw.get("subsurfaceBlock");
                    v.subsurfaceBlock = subsurfaceObj != null ? subsurfaceObj.toString() : null;
                    v.frequency = num(raw.get("frequency"), v.frequency);
                    v.threshold = num(raw.get("threshold"), v.threshold);
                    if (v.surfaceBlock == null && v.subsurfaceBlock == null) {
                        logger.warning("[NexusDimensions] Preset '" + preset.id + "': palette.variants entry '"
                                + v.name + "' sets neither surfaceBlock nor subsurfaceBlock - it can never do anything, skipped.");
                        continue;
                    }
                    variants.add(v);
                }
                p.variants = variants;
            }

            List<Map<?, ?>> rawDeposits = paletteSec.getMapList("glowDeposits");
            if (!rawDeposits.isEmpty()) {
                List<DimensionPreset.GlowDeposit> deposits = new ArrayList<>();
                for (Map<?, ?> raw : rawDeposits) {
                    DimensionPreset.GlowDeposit dep = new DimensionPreset.GlowDeposit();
                    dep.block = str(raw.get("block"), dep.block);
                    dep.frequency = num(raw.get("frequency"), dep.frequency);
                    dep.threshold = num(raw.get("threshold"), dep.threshold);
                    deposits.add(dep);
                }
                p.glowDeposits = deposits;
            }
        }

        ConfigurationSection biomesSec = yaml.getConfigurationSection("biomes");
        if (biomesSec != null) {
            DimensionPreset.Biomes b = preset.biomes;
            b.mode = biomesSec.getString("mode", b.mode);
            List<Map<?, ?>> rawEntries = biomesSec.getMapList("entries");
            if (!rawEntries.isEmpty()) {
                List<DimensionPreset.BiomeEntry> entries = new ArrayList<>();
                for (Map<?, ?> raw : rawEntries) {
                    DimensionPreset.BiomeEntry entry = new DimensionPreset.BiomeEntry();
                    Object idObj = raw.get("id");
                    if (idObj != null) {
                        entry.id = idObj.toString();
                    }
                    Object weightObj = raw.get("weight");
                    if (weightObj instanceof Number number) {
                        entry.weight = number.doubleValue();
                    }
                    entries.add(entry);
                }
                b.entries = entries;
            }
        }

        List<Map<?, ?>> rawCustomBiomes = yaml.getMapList("customBiomes");
        if (!rawCustomBiomes.isEmpty()) {
            List<DimensionPreset.CustomBiome> customBiomes = new ArrayList<>();
            for (Map<?, ?> raw : rawCustomBiomes) {
                DimensionPreset.CustomBiome cb = new DimensionPreset.CustomBiome();
                cb.id = str(raw.get("id"), null);
                cb.category = str(raw.get("category"), cb.category);
                cb.temperature = num(raw.get("temperature"), cb.temperature);
                cb.downfall = num(raw.get("downfall"), cb.downfall);
                cb.skyColor = str(raw.get("skyColor"), cb.skyColor);
                cb.fogColor = str(raw.get("fogColor"), cb.fogColor);
                cb.waterColor = str(raw.get("waterColor"), cb.waterColor);
                cb.waterFogColor = str(raw.get("waterFogColor"), cb.waterFogColor);
                if (cb.id != null) {
                    customBiomes.add(cb);
                } else {
                    logger.warning("[NexusDimensions] Preset '" + preset.id + "': customBiomes entry missing 'id', skipped.");
                }
            }
            preset.customBiomes = customBiomes;
        }

        ConfigurationSection treesSec = yaml.getConfigurationSection("trees");
        if (treesSec != null) {
            DimensionPreset.Trees tr = preset.trees;
            tr.enabled = treesSec.getBoolean("enabled", tr.enabled);
            tr.minHeight = treesSec.getInt("minHeight", tr.minHeight);
            tr.maxHeight = treesSec.getInt("maxHeight", tr.maxHeight);
            tr.canopyRadius = treesSec.getInt("canopyRadius", tr.canopyRadius);
            tr.trunkBlock = treesSec.getString("trunkBlock", tr.trunkBlock);
            tr.leafBlock = treesSec.getString("leafBlock", tr.leafBlock);
            tr.rarityPerChunk = treesSec.getDouble("rarityPerChunk", tr.rarityPerChunk);
            tr.giantCanopyLayers = treesSec.getInt("giantCanopyLayers", tr.giantCanopyLayers);
            tr.branches = treesSec.getBoolean("branches", tr.branches);
            tr.buttressRoots = treesSec.getBoolean("buttressRoots", tr.buttressRoots);
            tr.canopyAccentBlock = treesSec.getString("canopyAccentBlock", tr.canopyAccentBlock);
            tr.canopyAccentChance = treesSec.getDouble("canopyAccentChance", tr.canopyAccentChance);
            tr.trunkAccentBlock = treesSec.getString("trunkAccentBlock", tr.trunkAccentBlock);
            tr.trunkAccentChance = treesSec.getDouble("trunkAccentChance", tr.trunkAccentChance);
            tr.vineBlock = treesSec.getString("vineBlock", tr.vineBlock);
            tr.vineChance = treesSec.getDouble("vineChance", tr.vineChance);
            tr.vineMinLength = treesSec.getInt("vineMinLength", tr.vineMinLength);
            tr.vineMaxLength = treesSec.getInt("vineMaxLength", tr.vineMaxLength);

            List<Map<?, ?>> rawSpecies = treesSec.getMapList("species");
            if (!rawSpecies.isEmpty()) {
                List<DimensionPreset.TreeSpecies> species = new ArrayList<>();
                for (Map<?, ?> raw : rawSpecies) {
                    DimensionPreset.TreeSpecies s = new DimensionPreset.TreeSpecies();
                    s.name = str(raw.get("name"), s.name);
                    s.weight = Math.max(0.0, num(raw.get("weight"), s.weight));
                    s.minHeight = (int) num(raw.get("minHeight"), s.minHeight);
                    s.maxHeight = (int) num(raw.get("maxHeight"), s.maxHeight);
                    s.canopyRadius = (int) num(raw.get("canopyRadius"), s.canopyRadius);
                    s.trunkBlock = str(raw.get("trunkBlock"), s.trunkBlock);
                    s.leafBlock = str(raw.get("leafBlock"), s.leafBlock);
                    s.giantCanopyLayers = (int) num(raw.get("giantCanopyLayers"), s.giantCanopyLayers);
                    if (raw.get("branches") instanceof Boolean b) {
                        s.branches = b;
                    }
                    if (raw.get("buttressRoots") instanceof Boolean b) {
                        s.buttressRoots = b;
                    }
                    Object canopyAccentObj = raw.get("canopyAccentBlock");
                    s.canopyAccentBlock = canopyAccentObj != null ? canopyAccentObj.toString() : null;
                    s.canopyAccentChance = num(raw.get("canopyAccentChance"), s.canopyAccentChance);
                    Object trunkAccentObj = raw.get("trunkAccentBlock");
                    s.trunkAccentBlock = trunkAccentObj != null ? trunkAccentObj.toString() : null;
                    s.trunkAccentChance = num(raw.get("trunkAccentChance"), s.trunkAccentChance);
                    Object vineObj = raw.get("vineBlock");
                    s.vineBlock = vineObj != null ? vineObj.toString() : null;
                    s.vineChance = num(raw.get("vineChance"), s.vineChance);
                    s.vineMinLength = (int) num(raw.get("vineMinLength"), s.vineMinLength);
                    s.vineMaxLength = (int) num(raw.get("vineMaxLength"), s.vineMaxLength);
                    species.add(s);
                }
                double totalWeight = species.stream().mapToDouble(s -> s.weight).sum();
                if (totalWeight <= 0) {
                    logger.warning("[NexusDimensions] Preset '" + preset.id
                            + "': trees.species entries all have weight <= 0 — ignoring the list, "
                            + "falling back to the single-species trees.* fields.");
                } else {
                    long distinctNames = species.stream().map(s -> s.name).distinct().count();
                    if (distinctNames != species.size()) {
                        logger.warning("[NexusDimensions] Preset '" + preset.id
                                + "': trees.species has duplicate 'name' values — each species is cached by name "
                                + "for block-data reuse, so duplicates will silently share the first entry's "
                                + "trunk/leaf blocks. Give every species a unique name.");
                    }
                    tr.species = species;
                }
            }
        }

        ConfigurationSection decorationsSec = yaml.getConfigurationSection("decorations");
        if (decorationsSec != null) {
            DimensionPreset.Decorations dc = preset.decorations;
            dc.enabled = decorationsSec.getBoolean("enabled", dc.enabled);
            dc.perChunkAttempts = Math.max(0, decorationsSec.getInt("perChunkAttempts", dc.perChunkAttempts));
            dc.chancePerAttempt = decorationsSec.getDouble("chancePerAttempt", dc.chancePerAttempt);

            List<Map<?, ?>> rawDecoSpecies = decorationsSec.getMapList("species");
            if (!rawDecoSpecies.isEmpty()) {
                List<DimensionPreset.DecorationSpecies> list = new ArrayList<>();
                for (Map<?, ?> raw : rawDecoSpecies) {
                    DimensionPreset.DecorationSpecies s = new DimensionPreset.DecorationSpecies();
                    s.name = str(raw.get("name"), s.name);
                    s.weight = Math.max(0.0, num(raw.get("weight"), s.weight));
                    s.block = str(raw.get("block"), s.block);
                    s.minHeight = Math.max(1, (int) num(raw.get("minHeight"), s.minHeight));
                    s.maxHeight = Math.max(s.minHeight, (int) num(raw.get("maxHeight"), s.maxHeight));
                    Object capObj = raw.get("capBlock");
                    s.capBlock = capObj != null ? capObj.toString() : null;
                    s.capRadius = Math.max(0, (int) num(raw.get("capRadius"), s.capRadius));
                    s.minFloatHeight = Math.max(0, (int) num(raw.get("minFloatHeight"), s.minFloatHeight));
                    s.maxFloatHeight = Math.max(s.minFloatHeight, (int) num(raw.get("maxFloatHeight"), s.maxFloatHeight));
                    list.add(s);
                }
                double totalWeight = list.stream().mapToDouble(s -> s.weight).sum();
                if (totalWeight <= 0) {
                    logger.warning("[NexusDimensions] Preset '" + preset.id
                            + "': decorations.species entries all have weight <= 0 — ignoring the list.");
                } else {
                    dc.species = list;
                }
            }
            if (dc.enabled && dc.species.isEmpty()) {
                logger.warning("[NexusDimensions] Preset '" + preset.id
                        + "': decorations.enabled is true but no valid species configured — disabling.");
                dc.enabled = false;
            }
        }

        ConfigurationSection structuresSec = yaml.getConfigurationSection("structures");
        if (structuresSec != null) {
            DimensionPreset.Structures s = preset.structures;
            s.enabled = structuresSec.getBoolean("enabled", s.enabled);
            s.blueprint = structuresSec.getString("blueprint", s.blueprint);
            s.rarityPerChunk = structuresSec.getDouble("rarityPerChunk", s.rarityPerChunk);
            s.lootTable = structuresSec.getString("lootTable", s.lootTable);
            s.randomRotation = structuresSec.getBoolean("randomRotation", s.randomRotation);
            s.randomMirror = structuresSec.getBoolean("randomMirror", s.randomMirror);
            if (s.enabled && (s.blueprint == null || s.blueprint.isBlank())) {
                logger.warning("[NexusDimensions] Preset '" + preset.id
                        + "': structures.enabled is true but no blueprint is set — disabling.");
                s.enabled = false;
            }
        }

        ConfigurationSection creaturesSec = yaml.getConfigurationSection("creatures");
        if (creaturesSec != null) {
            DimensionPreset.Creatures cr = preset.creatures;
            cr.enabled = creaturesSec.getBoolean("enabled", cr.enabled);
            cr.spawnMultiplier = creaturesSec.getDouble("spawnMultiplier", cr.spawnMultiplier);
            if (cr.spawnMultiplier > 1.0) {
                logger.warning("[NexusDimensions] Preset '" + preset.id + "': creatures.spawnMultiplier ("
                        + cr.spawnMultiplier + ") is above 1.0, which isn't supported (no vanilla hook to spawn "
                        + "*more* than vanilla already attempts) — clamping to 1.0.");
                cr.spawnMultiplier = 1.0;
            } else if (cr.spawnMultiplier < 0) {
                cr.spawnMultiplier = 0;
            }

            ConfigurationSection mobsSec = creaturesSec.getConfigurationSection("mobs");
            if (mobsSec != null) {
                Map<String, DimensionPreset.MobProfile> mobs = new LinkedHashMap<>();
                for (String typeKey : mobsSec.getKeys(false)) {
                    String normalized = typeKey.toUpperCase(java.util.Locale.ROOT);
                    try {
                        org.bukkit.entity.EntityType.valueOf(normalized);
                    } catch (IllegalArgumentException ex) {
                        logger.warning("[NexusDimensions] Preset '" + preset.id + "': creatures.mobs entry '"
                                + typeKey + "' isn't a known org.bukkit.entity.EntityType name — skipped.");
                        continue;
                    }
                    ConfigurationSection mobSec = mobsSec.getConfigurationSection(typeKey);
                    DimensionPreset.MobProfile profile = new DimensionPreset.MobProfile();
                    if (mobSec != null) {
                        profile.displayName = mobSec.getString("displayName", profile.displayName);
                        profile.alwaysShowName = mobSec.getBoolean("alwaysShowName", profile.alwaysShowName);
                        profile.healthMultiplier = Math.max(0.01, mobSec.getDouble("healthMultiplier", profile.healthMultiplier));
                        profile.speedMultiplier = Math.max(0.01, mobSec.getDouble("speedMultiplier", profile.speedMultiplier));
                        profile.damageMultiplier = Math.max(0.0, mobSec.getDouble("damageMultiplier", profile.damageMultiplier));
                        profile.scale = Math.max(0.05, mobSec.getDouble("scale", profile.scale));
                        profile.glowing = mobSec.getBoolean("glowing", profile.glowing);

                        ConfigurationSection equipSec = mobSec.getConfigurationSection("equipment");
                        if (equipSec != null) {
                            Map<String, String> equipment = new LinkedHashMap<>();
                            for (String slot : equipSec.getKeys(false)) {
                                equipment.put(slot.toLowerCase(java.util.Locale.ROOT), equipSec.getString(slot));
                            }
                            profile.equipment = equipment;
                        }
                    }
                    mobs.put(normalized, profile);
                }
                cr.mobs = mobs;
            }
            if (cr.enabled && cr.mobs.isEmpty() && cr.spawnMultiplier >= 1.0) {
                logger.warning("[NexusDimensions] Preset '" + preset.id
                        + "': creatures.enabled is true but no valid mobs/spawnMultiplier < 1.0 configured — nothing to do.");
            }
        }

        ConfigurationSection seasonsSec = yaml.getConfigurationSection("seasons");
        if (seasonsSec != null) {
            DimensionPreset.Seasons se = preset.seasons;
            se.enabled = seasonsSec.getBoolean("enabled", se.enabled);
            List<Map<?, ?>> rawStages = seasonsSec.getMapList("stages");
            List<DimensionPreset.SeasonStage> stages = new ArrayList<>();
            for (Map<?, ?> raw : rawStages) {
                DimensionPreset.SeasonStage stage = new DimensionPreset.SeasonStage();
                stage.name = str(raw.get("name"), stage.name);
                stage.durationTicks = Math.max(20, (int) num(raw.get("durationTicks"), stage.durationTicks));
                if (raw.get("spawnMultiplierOverride") instanceof Number number) {
                    stage.spawnMultiplierOverride = Math.max(0.0, number.doubleValue());
                }
                if (raw.get("forceClearWeather") instanceof Boolean b) {
                    stage.forceClearWeather = b;
                }
                Object particlesObj = raw.get("particles");
                if (particlesObj instanceof Map<?, ?> particlesMap) {
                    stage.particles = parseParticlesMap(particlesMap, preset.id, stage.name);
                }
                stages.add(stage);
            }
            if (se.enabled && stages.isEmpty()) {
                logger.warning("[NexusDimensions] Preset '" + preset.id
                        + "': seasons.enabled is true but no stages are defined — disabling.");
                se.enabled = false;
            }
            se.stages = stages;
        }

        ConfigurationSection flavorSec = yaml.getConfigurationSection("flavor");
        if (flavorSec != null) {
            DimensionPreset.Flavor f = preset.flavor;
            f.gravity = flavorSec.getDouble("gravity", f.gravity);
            if (flavorSec.contains("allowJumping")) {
                f.allowJumping = flavorSec.getBoolean("allowJumping");
            }
            f.alwaysClearWeather = flavorSec.getBoolean("alwaysClearWeather", f.alwaysClearWeather);
            f.generateStructures = flavorSec.getBoolean("generateStructures", f.generateStructures);
            f.generateDecorations = flavorSec.getBoolean("generateDecorations", f.generateDecorations);
            f.generateVanillaCaves = flavorSec.getBoolean("generateVanillaCaves", f.generateVanillaCaves);
            if (f.gravity <= 0) {
                logger.warning("[NexusDimensions] Preset '" + preset.id + "': flavor.gravity must be > 0, resetting to 1.0.");
                f.gravity = 1.0;
            }
        }

        ConfigurationSection particlesSec = yaml.getConfigurationSection("particles");
        if (particlesSec != null) {
            DimensionPreset.Particles p = preset.particles;
            p.enabled = particlesSec.getBoolean("enabled", p.enabled);
            String rawType = particlesSec.getString("type", p.type);
            String normalized = (rawType.contains(":") ? rawType.substring(rawType.indexOf(':') + 1) : rawType)
                    .toUpperCase(java.util.Locale.ROOT);
            try {
                org.bukkit.Particle.valueOf(normalized);
                p.type = normalized;
            } catch (IllegalArgumentException ex) {
                logger.warning("[NexusDimensions] Preset '" + preset.id + "': particles.type '" + rawType
                        + "' isn't a valid org.bukkit.Particle name, defaulting to ASH.");
                p.type = "ASH";
            }
            p.color = particlesSec.getString("color", p.color);
            p.toColor = particlesSec.getString("toColor", p.toColor);
            p.size = (float) particlesSec.getDouble("size", p.size);
            p.density = particlesSec.getInt("density", p.density);
            p.radius = particlesSec.getInt("radius", p.radius);
            p.heightSpread = particlesSec.getInt("heightSpread", p.heightSpread);
            p.intervalTicks = Math.max(1, particlesSec.getInt("intervalTicks", p.intervalTicks));
            p.windStrength = particlesSec.getDouble("windStrength", p.windStrength);
        }

        // Cross-field validation: giant trees need a tall enough world - check every
        // species' maxHeight when a palette is configured, not just the single-species fallback.
        if (preset.trees.enabled) {
            DimensionPreset.WorldHeight resolved = preset.resolvedWorldHeight();
            int availableHeight = resolved != null ? resolved.height : 384;
            int tallestConfigured = preset.trees.species.isEmpty()
                    ? preset.trees.maxHeight
                    : preset.trees.species.stream().mapToInt(s -> s.maxHeight).max().orElse(preset.trees.maxHeight);
            if (tallestConfigured > availableHeight - 16) {
                logger.warning("[NexusDimensions] Preset '" + preset.id + "': the tallest configured tree height ("
                        + tallestConfigured + ") does not comfortably fit the resolved world height ("
                        + availableHeight + "). Add/raise a worldHeight block (Tier 2) or lower it.");
            }
        }

        return preset;
    }

    /** Same field set/validation as the top-level `particles` block, applied to a season stage's nested override map. */
    private DimensionPreset.Particles parseParticlesMap(Map<?, ?> raw, String presetId, String stageName) {
        DimensionPreset.Particles p = new DimensionPreset.Particles();
        p.enabled = raw.get("enabled") instanceof Boolean b ? b : true; // presence of the block implies "on" for this stage
        String rawType = str(raw.get("type"), p.type);
        String normalized = (rawType.contains(":") ? rawType.substring(rawType.indexOf(':') + 1) : rawType)
                .toUpperCase(java.util.Locale.ROOT);
        try {
            org.bukkit.Particle.valueOf(normalized);
            p.type = normalized;
        } catch (IllegalArgumentException ex) {
            logger.warning("[NexusDimensions] Preset '" + presetId + "': seasons stage '" + stageName
                    + "' particles.type '" + rawType + "' isn't a valid org.bukkit.Particle name, defaulting to ASH.");
            p.type = "ASH";
        }
        p.color = str(raw.get("color"), p.color);
        Object toColorObj = raw.get("toColor");
        p.toColor = toColorObj != null ? toColorObj.toString() : null;
        p.size = (float) num(raw.get("size"), p.size);
        p.density = (int) num(raw.get("density"), p.density);
        p.radius = (int) num(raw.get("radius"), p.radius);
        p.heightSpread = (int) num(raw.get("heightSpread"), p.heightSpread);
        p.intervalTicks = Math.max(1, (int) num(raw.get("intervalTicks"), p.intervalTicks));
        p.windStrength = num(raw.get("windStrength"), p.windStrength);
        return p;
    }

    private static String str(Object o, String def) {
        return o != null ? o.toString() : def;
    }

    private static double num(Object o, double def) {
        return o instanceof Number number ? number.doubleValue() : def;
    }
}
