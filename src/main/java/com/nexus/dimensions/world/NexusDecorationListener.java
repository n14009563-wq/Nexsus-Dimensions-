package com.nexus.dimensions.world;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.DecorationShaper;
import com.nexus.dimensions.generation.DecorationSpeciesPicker;
import com.nexus.dimensions.generation.DeterministicHash;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Tier 2 counterpart to {@code DecorationPopulator} — same relationship
 * as {@link NexusFloraListener} is to {@code GiantTreePopulator}: Tier 2
 * worlds never hand us a {@code ChunkGenerator}, so this places the same
 * ground clutter directly through the live {@link World} on {@link
 * ChunkLoadEvent} instead, using {@link World#getHighestBlockYAt} for
 * ground height.
 */
public final class NexusDecorationListener implements Listener {

    private final DimensionManager dimensionManager;

    public NexusDecorationListener(Plugin plugin, DimensionManager dimensionManager) {
        this.dimensionManager = dimensionManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            return;
        }
        World world = event.getWorld();
        DimensionPreset preset = dimensionManager.getPresetForWorld(world.getName());
        if (preset == null || !preset.isTier2() || !preset.decorations.enabled || preset.decorations.species.isEmpty()) {
            return;
        }

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        for (int attempt = 0; attempt < preset.decorations.perChunkAttempts; attempt++) {
            int salt = 900 + attempt * 10;
            if (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, salt + 1) > preset.decorations.chancePerAttempt) {
                continue;
            }

            int localX = (int) (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, salt + 2) * 16);
            int localZ = (int) (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, salt + 3) * 16);
            int worldX = (chunkX << 4) + localX;
            int worldZ = (chunkZ << 4) + localZ;

            int groundY = world.getHighestBlockYAt(worldX, worldZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);

            DimensionPreset.DecorationSpecies species =
                    DecorationSpeciesPicker.pick(preset.decorations.species, world.getSeed(), chunkX, chunkZ, attempt);

            DecorationShaper.place(species, world.getSeed(), chunkX, chunkZ, attempt, worldX, groundY, worldZ,
                    minY, maxY, (x, y, z, data) -> {
                        if (y >= minY && y < maxY) {
                            world.getBlockAt(x, y, z).setBlockData(data, false);
                        }
                    });
        }
    }
}
