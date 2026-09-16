package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.noise.NoiseUtil;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/**
 * Shared trunk+canopy placement algorithm, used by both {@link
 * GiantTreePopulator} (Tier 1, writes through a {@code LimitedRegion})
 * and {@code NexusFloraListener} (Tier 2, writes through a live {@code
 * World}). See DESIGN.md section 5 for why there are two call sites.
 * <p>
 * Beyond the basic trunk-and-tapered-canopy shape, this also (all
 * per-{@link DimensionPreset.TreeSpecies} config, all optional except the
 * first two which default on): perturbs the canopy silhouette with smooth
 * noise instead of drawing a perfect radial taper, grows 1-3 secondary
 * branches with their own mini-canopy on tall enough trees, flares
 * buttress roots at the base, replaces occasional trunk/canopy blocks with
 * an "accent" block (bioluminescent, crystalline, fruiting, whatever the
 * species wants), and grows hanging vine strands from qualifying canopy-
 * underside positions. Every random choice here is deterministic (a mix
 * of {@link DeterministicHash} for scattered/discrete choices and {@link
 * NoiseUtil#perlin3} for smooth continuous ones), so regenerating an
 * unexplored chunk after a restart always reproduces the exact same tree.
 */
public final class TreeShaper {

    private TreeShaper() {
    }

    /** Anything that can accept a block write, in-bounds-checked by the caller. */
    public interface BlockWriter {
        void set(int x, int y, int z, BlockData data);
    }

    public static void place(DimensionPreset.TreeSpecies species, NoiseUtil noise, long seed,
                              int worldX, int groundY, int worldZ, int worldMaxY,
                              double heightRoll, BlockData trunk, BlockData leaves, BlockWriter writer) {
        int trunkHeight = species.minHeight + (int) Math.round(heightRoll * (species.maxHeight - species.minHeight));
        trunkHeight = Math.min(trunkHeight, worldMaxY - groundY - 2);
        if (trunkHeight < 3) {
            return; // not enough headroom here, skip rather than draw a stub
        }

        BlockData trunkAccent = dataOf(species.trunkAccentBlock);
        BlockData canopyAccent = dataOf(species.canopyAccentBlock);
        BlockData vine = dataOf(species.vineBlock);

        placeTrunk(species, seed, worldX, groundY, worldZ, trunkHeight, trunk, trunkAccent, writer);

        int canopyBase = groundY + trunkHeight - Math.max(2, species.giantCanopyLayers / 3);
        int canopyTop = groundY + trunkHeight + Math.max(2, species.giantCanopyLayers / 2);
        placeCanopy(species, noise, seed, worldX, worldZ, canopyBase, canopyTop, groundY + trunkHeight,
                species.canopyRadius, leaves, canopyAccent, vine, writer);

        if (species.buttressRoots) {
            placeButtressRoots(species, seed, worldX, groundY, worldZ, trunk, writer);
        }

        if (species.branches && trunkHeight >= 18) {
            placeBranches(species, noise, seed, worldX, groundY, worldZ, trunkHeight, trunk, leaves,
                    trunkAccent, canopyAccent, writer);
        }
    }

    private static void placeTrunk(DimensionPreset.TreeSpecies species, long seed, int worldX, int groundY,
                                    int worldZ, int trunkHeight, BlockData trunk, BlockData trunkAccent,
                                    BlockWriter writer) {
        for (int i = 1; i <= trunkHeight; i++) {
            int y = groundY + i;
            BlockData block = trunk;
            if (trunkAccent != null && blockRoll(seed, worldX, y, worldZ, 700) < species.trunkAccentChance) {
                block = trunkAccent;
            }
            writer.set(worldX, y, worldZ, block);
        }
    }

    private static void placeCanopy(DimensionPreset.TreeSpecies species, NoiseUtil noise, long seed,
                                     int worldX, int worldZ, int canopyBase, int canopyTop, int trunkTop,
                                     int baseRadius, BlockData leaves, BlockData canopyAccent, BlockData vine,
                                     BlockWriter writer) {
        for (int y = canopyBase; y <= canopyTop; y++) {
            double heightFrac = (y - canopyBase) / (double) Math.max(1, canopyTop - canopyBase);
            double radiusFrac = 1.0 - Math.abs(heightFrac - 0.35) / 0.75; // fattest a bit above the base
            int layerRadius = Math.max(1, (int) Math.round(baseRadius * Math.max(0.15, radiusFrac)));

            for (int dx = -layerRadius; dx <= layerRadius; dx++) {
                for (int dz = -layerRadius; dz <= layerRadius; dz++) {
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    // Smooth per-direction wobble instead of a perfect circle - reads as an
                    // organic, lumpy canopy silhouette rather than a geometric sphere/cone.
                    double wobble = noise.perlin3((worldX + dx) * 0.15 + 4000, y * 0.15, (worldZ + dz) * 0.15 + 4000);
                    double effectiveRadius = layerRadius * (1.0 + wobble * 0.3);
                    if (dist > effectiveRadius) {
                        continue;
                    }
                    if (dx == 0 && dz == 0 && y <= trunkTop) {
                        continue; // keep the trunk block visible below the canopy
                    }

                    int x = worldX + dx;
                    int z = worldZ + dz;
                    BlockData block = leaves;
                    if (canopyAccent != null && blockRoll(seed, x, y, z, 710) < species.canopyAccentChance) {
                        block = canopyAccent;
                    }
                    writer.set(x, y, z, block);

                    // Hanging vines: only from the lower ~30% of the canopy, where "underside" reads
                    // naturally, and only when this exact position rolls in - keeps strands sparse
                    // rather than every leaf block growing one.
                    if (vine != null && heightFrac < 0.3
                            && blockRoll(seed, x, y, z, 720) < species.vineChance) {
                        int length = species.vineMinLength + (int) (blockRoll(seed, x, y, z, 721)
                                * Math.max(1, species.vineMaxLength - species.vineMinLength));
                        for (int v = 1; v <= length; v++) {
                            writer.set(x, y - v, z, vine);
                        }
                    }
                }
            }
        }
    }

    /** Short root flares radiating outward from the trunk base in eight directions, tapering by chance per direction. */
    private static void placeButtressRoots(DimensionPreset.TreeSpecies species, long seed, int worldX, int groundY,
                                            int worldZ, BlockData trunk, BlockWriter writer) {
        int[][] directions = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};
        for (int i = 0; i < directions.length; i++) {
            double roll = blockRoll(seed, worldX, groundY, worldZ, 730 + i);
            int length = 2 + (int) (roll * 3); // 2-4 blocks
            int dx = directions[i][0];
            int dz = directions[i][1];
            for (int step = 1; step <= length; step++) {
                writer.set(worldX + dx * step, groundY, worldZ + dz * step, trunk);
                if (step <= 2) {
                    // a little vertical thickness near the trunk so it reads as a flare, not a floor tile
                    writer.set(worldX + dx * step, groundY + 1, worldZ + dz * step, trunk);
                }
            }
        }
    }

    /**
     * 1-3 secondary limbs on tall trees, each walked outward+upward from a
     * point partway up the trunk with a small mini-canopy at the tip.
     * Deliberately a much smaller/cheaper shape than {@link #placeCanopy}
     * (a single-radius blob, no wobble/accents/vines) to keep branch count
     * from becoming a per-tree cost blowup.
     */
    private static void placeBranches(DimensionPreset.TreeSpecies species, NoiseUtil noise, long seed,
                                       int worldX, int groundY, int worldZ, int trunkHeight,
                                       BlockData trunk, BlockData leaves, BlockData trunkAccent,
                                       BlockData canopyAccent, BlockWriter writer) {
        int branchCount = 1 + (int) (blockRoll(seed, worldX, groundY, worldZ, 800) * 3); // 1-3
        for (int b = 0; b < branchCount; b++) {
            int salt = 810 + b * 10;
            double startFrac = 0.45 + blockRoll(seed, worldX, groundY, worldZ, salt + 1) * 0.35;
            double angle = blockRoll(seed, worldX, groundY, worldZ, salt + 2) * 2 * Math.PI;
            double lengthFrac = 0.25 + blockRoll(seed, worldX, groundY, worldZ, salt + 3) * 0.25;

            int startY = groundY + (int) Math.round(trunkHeight * startFrac);
            int length = Math.max(3, (int) Math.round(trunkHeight * lengthFrac));
            double dxPerStep = Math.cos(angle) * 0.7;
            double dzPerStep = Math.sin(angle) * 0.7;

            double x = worldX;
            double y = startY;
            double z = worldZ;
            for (int step = 0; step < length; step++) {
                x += dxPerStep;
                z += dzPerStep;
                y += 0.6; // rises at a shallower angle than it spreads
                int bx = (int) Math.round(x);
                int by = (int) Math.round(y);
                int bz = (int) Math.round(z);
                BlockData block = trunk;
                if (trunkAccent != null && blockRoll(seed, bx, by, bz, salt + 4) < species.trunkAccentChance) {
                    block = trunkAccent;
                }
                writer.set(bx, by, bz, block);
            }

            // Small mini-canopy blob at the tip.
            int tipX = (int) Math.round(x);
            int tipY = (int) Math.round(y);
            int tipZ = (int) Math.round(z);
            int miniRadius = Math.max(2, species.canopyRadius / 4);
            for (int dx = -miniRadius; dx <= miniRadius; dx++) {
                for (int dy = -miniRadius; dy <= miniRadius; dy++) {
                    for (int dz = -miniRadius; dz <= miniRadius; dz++) {
                        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                        if (dist > miniRadius) {
                            continue;
                        }
                        int lx = tipX + dx;
                        int ly = tipY + dy;
                        int lz = tipZ + dz;
                        BlockData block = leaves;
                        if (canopyAccent != null
                                && blockRoll(seed, lx, ly, lz, salt + 5) < species.canopyAccentChance) {
                            block = canopyAccent;
                        }
                        writer.set(lx, ly, lz, block);
                    }
                }
            }
        }
    }

    /** Deterministic per-block scatter roll, distinct from the smooth noise used for canopy wobble. */
    private static double blockRoll(long seed, int x, int y, int z, int salt) {
        return DeterministicHash.hash01(seed, x * 92821 + y, z, salt);
    }

    private static BlockData dataOf(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        Material m = Material.matchMaterial(key);
        return m != null ? m.createBlockData() : null;
    }
}
