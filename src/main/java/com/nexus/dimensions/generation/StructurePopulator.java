package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.structure.Blueprint;
import com.nexus.dimensions.structure.BlueprintTransform;
import com.nexus.dimensions.world.StructureLootService;
import org.bukkit.Material;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/**
 * Places a {@link Blueprint} at rare anchor chunks, the same
 * deterministic-hash pattern as {@link GiantTreePopulator} (different
 * salt channels so the two don't correlate when a preset has both trees
 * and structures enabled). Ground height comes from whichever {@link
 * GroundHeightSource} the owning generator used, so structures sit
 * correctly on heightmap ground or on top of a floating island exactly
 * like trees do.
 * <p>
 * Loot placement is two-phase — see {@link StructureLootService} for why:
 * this class only ever places a plain chest block through the (possibly
 * off-main-thread) {@code LimitedRegion} and enqueues the loot-table
 * assignment; it never touches {@code BlockState}/{@code Lootable} itself.
 */
public final class StructurePopulator extends BlockPopulator {

    private final DimensionPreset preset;
    private final Blueprint blueprint;
    private final GroundHeightSource groundHeightSource;
    private final StructureLootService lootService;

    public StructurePopulator(DimensionPreset preset, Blueprint blueprint, GroundHeightSource groundHeightSource,
                               StructureLootService lootService) {
        this.preset = preset;
        this.blueprint = blueprint;
        this.groundHeightSource = groundHeightSource;
        this.lootService = lootService;
    }

    private static Material materialOf(String key) {
        Material m = Material.matchMaterial(key);
        return m != null ? m : Material.STONE;
    }

    @Override
    public void populate(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, LimitedRegion limitedRegion) {
        DimensionPreset.Structures cfg = preset.structures;
        if (!cfg.enabled || blueprint == null || blueprint.blocks.isEmpty()) {
            return;
        }

        // Salts 20+ so structure anchors don't correlate with tree anchors (salts 1-4) on the same chunk.
        if (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 20) > cfg.rarityPerChunk) {
            return;
        }

        int localX = (int) (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 21) * 16);
        int localZ = (int) (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 22) * 16);
        int worldX = (chunkX << 4) + localX;
        int worldZ = (chunkZ << 4) + localZ;

        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight();
        int groundY = groundHeightSource.groundHeight(worldX, worldZ, minY, maxY);
        if (groundY <= minY) {
            return; // no solid ground found in this column (e.g. a gap between floating islands) - skip
        }

        // Salts 23-24, distinct from every other channel this class or GiantTreePopulator uses.
        int rotationStep = cfg.randomRotation
                ? (int) (DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 23) * 4)
                : 0;
        boolean mirror = cfg.randomMirror
                && DeterministicHash.hash01(worldInfo.getSeed(), chunkX, chunkZ, 24) < 0.5;

        for (Blueprint.BlockEntry entry : blueprint.blocks) {
            int[] transformed = BlueprintTransform.apply(entry.dx, entry.dz, rotationStep, mirror);
            int x = worldX + transformed[0];
            int y = groundY + 1 + entry.dy;
            int z = worldZ + transformed[1];
            if (y < minY || y >= maxY || !limitedRegion.isInRegionBounds(x, y, z)) {
                continue;
            }

            if (entry.loot) {
                limitedRegion.setBlockData(x, y, z, Material.CHEST.createBlockData());
                lootService.enqueue(worldInfo.getName(), x, y, z, cfg.lootTable);
            } else {
                limitedRegion.setBlockData(x, y, z, materialOf(entry.block).createBlockData());
            }
        }
    }
}
