package com.nexus.dimensions.world;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.NexusBiomeProvider;
import com.nexus.dimensions.generation.NexusChunkGenerator;
import com.nexus.dimensions.structure.Blueprint;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Creates and tracks Nexus dimensions. Also persists the world-name ->
 * preset-id mapping for Tier 1 worlds to {@code worlds.yml}, because
 * Bukkit does not remember which custom generator a world folder used —
 * every server boot we have to call {@link WorldCreator#generator} again
 * for previously created worlds or their un-generated chunks would fall
 * back to plain vanilla terrain.
 */
public final class DimensionManager implements Listener {

    public enum CreateResult {
        TIER1_CREATED,
        /** Created, but the world folder already had chunks on disk from before Nexus Dimensions touched it. */
        TIER1_CREATED_ON_PREEXISTING_FOLDER,
        TIER1_ALREADY_LOADED,
        TIER2_DATAPACK_WRITTEN_RESTART_REQUIRED,
        TIER2_ALREADY_ACTIVE,
        UNKNOWN_PRESET,
        NAME_COLLISION
    }

    private final Plugin plugin;
    private final Logger logger;
    private final File worldsFile;
    private Map<String, DimensionPreset> presets;
    private Map<String, Blueprint> blueprints;
    private final StructureLootService lootService;
    /**
     * Set once, after construction, from {@code onEnable} — {@link
     * SeasonService} itself needs a {@link DimensionManager} reference, so
     * a constructor-injected two-way dependency isn't possible without one
     * of them being built before the other exists. Nullable: seasons are
     * an optional feature, {@link #onWeatherChange} falls back to the
     * preset's plain {@code flavor.alwaysClearWeather} when this is unset.
     */
    private SeasonService seasonService;
    /** worldName -> presetId, persisted. */
    private final Map<String, String> managedWorlds = new LinkedHashMap<>();

    public DimensionManager(Plugin plugin, Map<String, DimensionPreset> presets, Map<String, Blueprint> blueprints,
                             StructureLootService lootService) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.presets = presets;
        this.blueprints = blueprints;
        this.lootService = lootService;
        this.worldsFile = new File(plugin.getDataFolder(), "worlds.yml");
        loadManagedWorlds();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void reloadPresets(Map<String, DimensionPreset> presets) {
        this.presets = presets;
    }

    public void reloadBlueprints(Map<String, Blueprint> blueprints) {
        this.blueprints = blueprints;
    }

    public void setSeasonService(SeasonService seasonService) {
        this.seasonService = seasonService;
    }

    public Blueprint getBlueprint(String name) {
        return name != null ? blueprints.get(name) : null;
    }

    public Map<String, DimensionPreset> getPresets() {
        return presets;
    }

    /** Looks up the preset that owns the given (already loaded) world, or null. */
    public DimensionPreset getPresetForWorld(String worldName) {
        String presetId = managedWorlds.get(worldName);
        if (presetId == null) {
            return null;
        }
        return presets.get(presetId);
    }

    private void loadManagedWorlds() {
        if (!worldsFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(worldsFile);
        for (String worldName : yaml.getKeys(false)) {
            managedWorlds.put(worldName, yaml.getString(worldName));
        }
    }

    private void saveManagedWorlds() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, String> e : managedWorlds.entrySet()) {
            yaml.set(e.getKey(), e.getValue());
        }
        try {
            yaml.save(worldsFile);
        } catch (IOException e) {
            logger.severe("[NexusDimensions] Could not save worlds.yml: " + e.getMessage());
        }
    }

    /** Call once from onEnable: re-attaches our generator to every previously created Tier 1 world. */
    public void loadPersistedWorldsOnStartup() {
        for (Map.Entry<String, String> entry : managedWorlds.entrySet()) {
            String worldName = entry.getKey();
            String presetId = entry.getValue();
            DimensionPreset preset = presets.get(presetId);
            if (preset == null) {
                logger.warning("[NexusDimensions] worlds.yml references unknown preset '" + presetId
                        + "' for world '" + worldName + "' — skipping, add the preset back or edit worlds.yml.");
                continue;
            }
            if (preset.isTier2()) {
                continue; // Tier 2 worlds are auto-loaded by vanilla from the datapack, not by us.
            }
            if (Bukkit.getWorld(worldName) == null) {
                loadTier1World(worldName, preset, null);
                logger.info("[NexusDimensions] Re-attached generator to existing dimension '" + worldName + "'.");
            }
        }
    }

    /**
     * Call once from onEnable, after {@link #loadPersistedWorldsOnStartup()}.
     * Finds Tier 2 dimensions vanilla already auto-loaded from a previously
     * written datapack (recognized by their {@code nexus:<presetId>} world
     * key) and registers them in-memory so gravity/weather/flora lookups
     * work for them too. Not persisted to worlds.yml — that file is only
     * for Tier 1 worlds we create ourselves via WorldCreator.
     */
    public void registerActiveTier2WorldsOnStartup() {
        for (World world : Bukkit.getWorlds()) {
            if (!"nexus".equals(world.getKey().getNamespace())) {
                continue;
            }
            String presetId = world.getKey().getKey();
            DimensionPreset preset = presets.get(presetId);
            if (preset == null || !preset.isTier2()) {
                continue;
            }
            managedWorlds.put(world.getName(), presetId);
            applyFlavor(world, preset);
            logger.info("[NexusDimensions] Recognized active Tier 2 dimension '" + presetId
                    + "' as world '" + world.getName() + "'.");
        }
    }

    public CreateResult createOrLoad(String worldName, String presetId, Long seedOverride) {
        DimensionPreset preset = presets.get(presetId);
        if (preset == null) {
            return CreateResult.UNKNOWN_PRESET;
        }

        if (preset.isTier2()) {
            boolean alreadyActive = Bukkit.getWorlds().stream()
                    .anyMatch(w -> "nexus".equals(w.getKey().getNamespace()) && w.getKey().getKey().equals(presetId));
            if (alreadyActive) {
                return CreateResult.TIER2_ALREADY_ACTIVE;
            }
            // Datapack (re)generation is handled by DatapackGenerator at startup / on demand;
            // the command layer calls that separately and reports the restart requirement.
            return CreateResult.TIER2_DATAPACK_WRITTEN_RESTART_REQUIRED;
        }

        World existing = Bukkit.getWorld(worldName);
        if (existing != null) {
            return managedWorlds.containsKey(worldName) ? CreateResult.TIER1_ALREADY_LOADED : CreateResult.NAME_COLLISION;
        }

        // Bukkit.getWorld() only sees *loaded* worlds. A folder can exist on disk
        // (an old vanilla world, a copy, a previous plugin's leftovers) without being
        // loaded yet. If we silently load that folder, every chunk already saved to
        // disk keeps its original terrain forever — only chunks generated from this
        // point on use our generator. That's exactly "spawn looks custom, everything
        // already-explored is still the old world" — so we detect it and say so
        // instead of letting it happen quietly.
        boolean preexisting = hasPreexistingChunkData(worldName);

        loadTier1World(worldName, preset, seedOverride);
        managedWorlds.put(worldName, presetId);
        saveManagedWorlds();
        return preexisting ? CreateResult.TIER1_CREATED_ON_PREEXISTING_FOLDER : CreateResult.TIER1_CREATED;
    }

    /** True if {@code <worldContainer>/<worldName>/region} already has saved chunk data. */
    private boolean hasPreexistingChunkData(String worldName) {
        File worldFolder = new File(Bukkit.getWorldContainer(), worldName);
        File regionFolder = new File(worldFolder, "region");
        String[] regionFiles = regionFolder.list((dir, name) -> name.endsWith(".mca"));
        return regionFiles != null && regionFiles.length > 0;
    }

    private World loadTier1World(String worldName, DimensionPreset preset, Long seedOverride) {
        WorldCreator creator = new WorldCreator(worldName);
        creator.environment(preset.environment);
        creator.type(WorldType.NORMAL);
        creator.generateStructures(preset.flavor.generateStructures);

        long seed = seedOverride != null ? seedOverride : (preset.seed != null ? preset.seed : worldName.hashCode());
        creator.seed(seed);

        NexusChunkGenerator generator = new NexusChunkGenerator(preset, seed, blueprints, lootService);
        creator.generator(generator);
        creator.biomeProvider(new NexusBiomeProvider(preset));

        World world = creator.createWorld();
        if (world != null) {
            applyFlavor(world, preset);
        }
        return world;
    }

    private void applyFlavor(World world, DimensionPreset preset) {
        // Explicit, not just relying on the server default: this world generates
        // uniformly out to vanilla's practical coordinate limit in every direction,
        // the same as every other Minecraft world. There is no "custom near spawn,
        // vanilla further out" behavior anywhere in NexusChunkGenerator — every
        // chunk the server ever asks for, at any coordinate, goes through the same
        // generateNoise() call using the same preset. Setting the border explicitly
        // here just makes that guarantee visible instead of implicit.
        world.getWorldBorder().setSize(59_999_968); // vanilla's own effective maximum

        if (preset.flavor.alwaysClearWeather) {
            world.setStorm(false);
            world.setThundering(false);
            world.setWeatherDuration(Integer.MAX_VALUE);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeatherChange(WeatherChangeEvent event) {
        if (!event.toWeatherState()) {
            return; // allow clearing up
        }
        String worldName = event.getWorld().getName();
        DimensionPreset preset = getPresetForWorld(worldName);
        if (preset == null) {
            return;
        }
        boolean alwaysClear = seasonService != null
                ? seasonService.effectiveAlwaysClearWeather(worldName, preset)
                : preset.flavor.alwaysClearWeather;
        if (alwaysClear) {
            event.setCancelled(true);
        }
    }
}
