package com.nexus.dimensions;

import com.nexus.dimensions.command.NexusDimCommand;
import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.config.PresetLoader;
import com.nexus.dimensions.datapack.DatapackGenerator;
import com.nexus.dimensions.generation.GiantTreePopulator;
import com.nexus.dimensions.structure.Blueprint;
import com.nexus.dimensions.structure.BlueprintLoader;
import com.nexus.dimensions.world.AmbientParticleService;
import com.nexus.dimensions.world.DimensionManager;
import com.nexus.dimensions.world.GravityService;
import com.nexus.dimensions.world.MobCustomizationListener;
import com.nexus.dimensions.world.NexusDecorationListener;
import com.nexus.dimensions.world.NexusFloraListener;
import com.nexus.dimensions.world.NexusStructureListener;
import com.nexus.dimensions.world.PortalListener;
import com.nexus.dimensions.world.PortalManager;
import com.nexus.dimensions.world.SeasonService;
import com.nexus.dimensions.world.StructureLootService;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.Map;

public final class NexusDimensionsPlugin extends JavaPlugin {

    /** Bundled inside the jar under resources/presets/, copied out on first run only. */
    private static final String[] DEFAULT_PRESETS = {
            "moon.yml", "ice_moon.yml", "ocean_planet.yml", "sky_forest.yml", "iron_giant_world.yml",
            "floating_isles.yml", "crystal_spires.yml"
    };

    /** Bundled inside the jar under resources/blueprints/, copied out on first run only. */
    private static final String[] DEFAULT_BLUEPRINTS = {
            "ruin_small.yml"
    };

    private PresetLoader presetLoader;
    private BlueprintLoader blueprintLoader;
    private DimensionManager dimensionManager;
    private DatapackGenerator datapackGenerator;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        copyBundledPresetsIfMissing();
        copyBundledBlueprintsIfMissing();

        this.presetLoader = new PresetLoader(this);
        Map<String, DimensionPreset> presets = presetLoader.loadAll();

        this.blueprintLoader = new BlueprintLoader(this);
        Map<String, Blueprint> blueprints = blueprintLoader.loadAll();

        StructureLootService lootService = new StructureLootService(this);

        // See config.yml - this MUST match the pack_format your exact server version
        // expects or Tier 2 datapacks can silently fail to activate. Default (61) is
        // correct for Minecraft 1.21.4; change config.yml if you're on a different
        // point release, then reload/restart - no new build needed.
        int packFormat = getConfig().getInt("datapackPackFormat", 61);
        this.datapackGenerator = new DatapackGenerator(getLogger(), packFormat);
        this.dimensionManager = new DimensionManager(this, presets, blueprints, lootService);

        // Refresh datapacks for every Tier 2 preset on every boot so edits to
        // an existing preset are picked up; brand new Tier 2 presets still
        // need one more restart after this to actually appear as a world.
        for (DimensionPreset preset : presets.values()) {
            if (preset.isTier2()) {
                datapackGenerator.writeDatapack(preset);
            }
        }

        dimensionManager.loadPersistedWorldsOnStartup();
        dimensionManager.registerActiveTier2WorldsOnStartup();

        PortalManager portalManager = new PortalManager(this);

        NexusDimCommand command = new NexusDimCommand(presetLoader, dimensionManager, datapackGenerator, blueprintLoader, portalManager);
        var pluginCommand = getCommand("nexusdim");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().severe("[NexusDimensions] 'nexusdim' command missing from plugin.yml — check the jar wasn't repackaged incorrectly.");
        }

        SeasonService seasonService = new SeasonService(this, dimensionManager);
        dimensionManager.setSeasonService(seasonService);

        new GravityService(this, dimensionManager);
        new AmbientParticleService(this, dimensionManager, seasonService);
        new NexusFloraListener(this, dimensionManager);
        new NexusDecorationListener(this, dimensionManager);
        new NexusStructureListener(this, dimensionManager, lootService);
        new PortalListener(this, portalManager);
        new MobCustomizationListener(this, dimensionManager, seasonService);

        getLogger().info("[NexusDimensions] Enabled with " + presets.size() + " preset(s) and " + blueprints.size()
                + " blueprint(s). Note: " + GiantTreePopulator.class.getSimpleName()
                + " and structure placement only attach to Tier 1 dimensions you create with /nexusdim create.");
    }

    private void copyBundledPresetsIfMissing() {
        File presetsDir = new File(getDataFolder(), "presets");
        if (!presetsDir.exists()) {
            presetsDir.mkdirs();
        }
        for (String fileName : DEFAULT_PRESETS) {
            File target = new File(presetsDir, fileName);
            if (!target.exists()) {
                saveResource("presets/" + fileName, false);
            }
        }
    }

    private void copyBundledBlueprintsIfMissing() {
        File blueprintsDir = new File(getDataFolder(), "blueprints");
        if (!blueprintsDir.exists()) {
            blueprintsDir.mkdirs();
        }
        for (String fileName : DEFAULT_BLUEPRINTS) {
            File target = new File(blueprintsDir, fileName);
            if (!target.exists()) {
                saveResource("blueprints/" + fileName, false);
            }
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("[NexusDimensions] Disabled.");
    }
}
