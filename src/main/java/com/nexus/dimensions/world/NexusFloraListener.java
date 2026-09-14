package com.nexus.dimensions.world;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.DeterministicHash;
import com.nexus.dimensions.generation.TreeShaper;
import com.nexus.dimensions.generation.TreeSpeciesPicker;
import com.nexus.dimensions.generation.noise.NoiseUtil;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;

/**
 * Tier 2 counterpart to {@code GiantTreePopulator}. Tier 2 worlds are
 * auto-loaded by vanilla from a datapack using a plain {@code
 * minecraft:flat} generator (see DESIGN.md section 6), so Nexus
 * Dimensions never gets a {@code ChunkGenerator} hook on them — instead
 * this listens for freshly generated chunks and grows the same
 * config-shaped trees directly through the live {@link World}, using
 * {@link World#getHighestBlockYAt} for ground height since the terrain
 * itself isn't ours to query analytically.
 */
public final class NexusFloraListener implements Listener {

    private final DimensionManager dimensionManager;
    private final Map<String, BlockData> trunkCache = new HashMap<>();
    private final Map<String, BlockData> leafCache = new HashMap<>();
    /**
     * Tier 2 worlds don't already have a NoiseUtil the way Tier 1's
     * NexusChunkGenerator does (there's no custom terrain generator on
     * these worlds at all) - one is built lazily per preset, from the same
     * effective seed {@code NexusChunkGenerator} would use, purely to
     * drive the canopy-wobble noise in {@code TreeShaper}.
     */
    private final Map<String, NoiseUtil> noiseCache = new HashMap<>();

    public NexusFloraListener(Plugin plugin, DimensionManager dimensionManager) {
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
        if (preset == null || !preset.isTier2() || !preset.trees.enabled) {
            return;
        }

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        if (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 1) > preset.trees.rarityPerChunk) {
            return;
        }

        int localX = (int) (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 2) * 16);
        int localZ = (int) (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 3) * 16);
        int worldX = (chunkX << 4) + localX;
        int worldZ = (chunkZ << 4) + localZ;

        int groundY = world.getHighestBlockYAt(worldX, worldZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        double heightRoll = DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 4);

        DimensionPreset.TreeSpecies species = TreeSpeciesPicker.pick(preset.trees, world.getSeed(), chunkX, chunkZ);
        String cacheKey = preset.id + ":" + species.name;
        BlockData trunk = trunkCache.computeIfAbsent(cacheKey, k -> materialOf(species.trunkBlock).createBlockData());
        BlockData leaves = leafCache.computeIfAbsent(cacheKey, k -> materialOf(species.leafBlock).createBlockData());
        NoiseUtil noise = noiseCache.computeIfAbsent(preset.id,
                id -> new NoiseUtil(preset.seed != null ? preset.seed : world.getSeed()));
        int maxY = world.getMaxHeight();

        TreeShaper.place(species, noise, world.getSeed(), worldX, groundY, worldZ, maxY, heightRoll, trunk, leaves,
                (x, y, z, data) -> {
                    if (y >= world.getMinHeight() && y < world.getMaxHeight()) {
                        world.getBlockAt(x, y, z).setBlockData(data, false);
                    }
                });
    }

    private static Material materialOf(String key) {
        Material m = Material.matchMaterial(key);
        return m != null ? m : Material.OAK_LOG;
    }
}
