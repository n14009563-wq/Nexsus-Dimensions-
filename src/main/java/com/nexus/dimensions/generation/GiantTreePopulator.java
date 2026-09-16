package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.noise.NoiseUtil;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Tier 1 flora placement: runs as a {@link BlockPopulator} attached to
 * {@link NexusChunkGenerator}, so it only ever sees worlds Nexus
 * Dimensions itself generated. Placement uses a deterministic per-chunk
 * hash instead of the {@link Random} handed to {@code populate}, so
 * re-generating an unexplored chunk after a restart always yields the
 * same tree in the same spot.
 * <p>
 * Ground height comes from whichever {@link GroundHeightSource}
 * {@link NexusChunkGenerator} actually used to paint the terrain
 * (heightmap or 3D density) — this class doesn't know or care which,
 * which is what lets the same tree code root correctly on flat ground or
 * off the top of a floating island.
 * <p>
 * Every write is guarded by {@link LimitedRegion#isInRegion}
 * because a wide canopy can spill into neighboring chunks outside the
 * region the engine hands this call; out-of-bounds writes are skipped
 * rather than failing. Keep {@code canopyRadius} roughly &lt;= 32 if you
 * want canopies to reliably avoid edge-clipping near chunk boundaries.
 * <p>
 * When {@code trees.species} is configured, {@link TreeSpeciesPicker}
 * chooses one species per anchor chunk (weighted random, deterministic);
 * block data for each species actually used is cached by name the first
 * time it's needed rather than rebuilt from a Material lookup every call.
 */
public final class GiantTreePopulator extends BlockPopulator {

    private final DimensionPreset preset;
    private final GroundHeightSource groundHeightSource;
    private final NoiseUtil noise;
    private final Map<String, BlockData> trunkCache = new HashMap<>();
    private final Map<String, BlockData> leafCache = new HashMap<>();

    public GiantTreePopulator(DimensionPreset preset, GroundHeightSource groundHeightSource, NoiseUtil noise) {
        this.preset = preset;
        this.groundHeightSource = groundHeightSource;
        this.noise = noise;
    }

    private static Material materialOf(String key) {
        Material m = Material.matchMaterial(key);
        return m != null ? m : Material.OAK_LOG;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion limitedRegion) {
        DimensionPreset.Trees cfg = preset.trees;
        if (!cfg.enabled) {
            return;
        }

        if (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 1) > cfg.rarityPerChunk) {
            return; // this chunk is not a tree anchor
        }

        int localX = (int) (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 2) * 16);
        int localZ = (int) (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 3) * 16);
        int worldX = (chunkX << 4) + localX;
        int worldZ = (chunkZ << 4) + localZ;

        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight();
        int groundY = groundHeightSource.groundHeight(worldX, worldZ, minY, maxY);
        double heightRoll = DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 4);

        DimensionPreset.TreeSpecies species = TreeSpeciesPicker.pick(cfg, worldInfo.getSeed(), chunkX, chunkZ);
        BlockData trunk = trunkCache.computeIfAbsent(species.name, n -> materialOf(species.trunkBlock).createBlockData());
        BlockData leaves = leafCache.computeIfAbsent(species.name, n -> materialOf(species.leafBlock).createBlockData());

        TreeShaper.place(species, noise, worldInfo.getSeed(), worldX, groundY, worldZ, maxY, heightRoll, trunk, leaves,
                (x, y, z, data) -> {
                    if (limitedRegion.isInRegion(x, y, z)) {
                        limitedRegion.setBlockData(x, y, z, data);
                    }
                });
    }
}
