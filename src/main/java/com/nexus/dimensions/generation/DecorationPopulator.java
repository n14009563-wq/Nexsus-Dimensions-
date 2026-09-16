package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Small ground-clutter decorations (boulders, crystal shards, fungal
 * caps, floating debris) — not trees, not structures, just texture. Rolls
 * up to {@code decorations.perChunkAttempts} independent placement
 * candidates per chunk, each on its own deterministic-hash salt channel
 * (900+, see {@link DecorationShaper}), so a single chunk can host several
 * decorations at once instead of the single-anchor-per-chunk approach
 * trees/structures use — clutter is meant to be common, not rare. See
 * DESIGN.md section 5c.
 */
public final class DecorationPopulator extends BlockPopulator {

    private final DimensionPreset preset;
    private final GroundHeightSource groundHeightSource;

    public DecorationPopulator(DimensionPreset preset, GroundHeightSource groundHeightSource) {
        this.preset = preset;
        this.groundHeightSource = groundHeightSource;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion limitedRegion) {
        DimensionPreset.Decorations cfg = preset.decorations;
        if (!cfg.enabled || cfg.species.isEmpty()) {
            return;
        }

        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight();

        for (int attempt = 0; attempt < cfg.perChunkAttempts; attempt++) {
            int salt = 900 + attempt * 10;
            if (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, salt + 1) > cfg.chancePerAttempt) {
                continue;
            }

            int localX = (int) (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, salt + 2) * 16);
            int localZ = (int) (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, salt + 3) * 16);
            int worldX = (chunkX << 4) + localX;
            int worldZ = (chunkZ << 4) + localZ;

            int groundY = groundHeightSource.groundHeight(worldX, worldZ, minY, maxY);
            if (groundY <= minY) {
                continue; // no ground here (e.g. a gap between floating islands) - skip
            }

            DimensionPreset.DecorationSpecies species =
                    DecorationSpeciesPicker.pick(cfg.species, worldInfo.getSeed(), chunkX, chunkZ, attempt);

            DecorationShaper.place(species, worldInfo.getSeed(), chunkX, chunkZ, attempt, worldX, groundY, worldZ,
                    minY, maxY, (x, y, z, data) -> {
                        if (limitedRegion.isInRegion(x, y, z)) {
                            limitedRegion.setBlockData(x, y, z, data);
                        }
                    });
        }
    }
}
