package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Shared decoration placement algorithm, used by both {@link
 * DecorationPopulator} (Tier 1) and {@code NexusDecorationListener}
 * (Tier 2) — same trunk/canopy split {@link TreeShaper} uses between the
 * two tiers, just for a much cheaper shape: a straight vertical column of
 * {@code species.block}, optionally topped with a flat disc of {@code
 * species.capBlock}. That one shape covers a boulder (height 1, no cap),
 * a rounded rock/mushroom cap (short column + capRadius &gt; 0), a
 * crystal spike (taller column + a single-block cap), and hovering alien
 * debris (a nonzero float height keeps the whole thing off the ground) —
 * see DESIGN.md section 5c.
 */
public final class DecorationShaper {

    private DecorationShaper() {
    }

    public static void place(DimensionPreset.DecorationSpecies species, long seed, int chunkX, int chunkZ,
                              int attempt, int worldX, int groundY, int worldZ, int minY, int maxY,
                              TreeShaper.BlockWriter writer) {
        int salt = 900 + attempt * 10;
        double heightRoll = DeterministicHash.hash01(seed, chunkX, chunkZ, salt + 5);
        double floatRoll = DeterministicHash.hash01(seed, chunkX, chunkZ, salt + 6);

        int height = species.minHeight + (int) Math.round(heightRoll * (species.maxHeight - species.minHeight));
        int floatOffset = species.minFloatHeight
                + (int) Math.round(floatRoll * (species.maxFloatHeight - species.minFloatHeight));
        int baseY = groundY + 1 + floatOffset;

        BlockData columnData = dataOf(species.block);
        if (columnData != null) {
            for (int h = 0; h < height; h++) {
                int y = baseY + h;
                if (y < minY || y >= maxY) {
                    continue;
                }
                writer.set(worldX, y, worldZ, columnData);
            }
        }

        BlockData capData = dataOf(species.capBlock);
        if (capData == null) {
            return;
        }
        int capY = baseY + height;
        if (capY < minY || capY >= maxY) {
            return;
        }
        if (species.capRadius <= 0) {
            writer.set(worldX, capY, worldZ, capData);
            return;
        }
        int r = species.capRadius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) {
                    continue;
                }
                writer.set(worldX + dx, capY, worldZ + dz, capData);
            }
        }
    }

    private static BlockData dataOf(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Material m = Material.matchMaterial(key);
        return m != null ? m.createBlockData() : null;
    }
}
