package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.noise.NoiseUtil;

import java.util.List;

/**
 * True 3D terrain: solid/air is a function of (x, y, z), not one height
 * per column. This is what makes overhangs, arches, and floating islands
 * possible at all — a heightmap fundamentally cannot represent more than
 * one surface per (x, z), no matter how the noise driving it is tuned.
 * <p>
 * The density formula is the standard shape for this kind of generator:
 * 3D fractal noise, biased toward "solid" near the center of one or more
 * altitude bands and toward "air" away from them. A block is solid where
 * {@code density(x,y,z) > threshold}. One band with a wide thickness
 * looks like ordinary ground with occasional overhangs; several narrow
 * bands at different heights read as distinct floating island layers;
 * the same noise carves caves through solid mass and stray floating
 * chunks outside the bands wherever it's locally strong enough — all one
 * formula, no separate crater/cave logic (see PresetLoader's warning if
 * those heightmap-only fields are set alongside this mode).
 * <p>
 * Surface/subsurface/deep classification checks face-adjacent density
 * samples rather than "distance below the heightmap," since a block can
 * be exposed to air from any direction here (the underside of an
 * overhang, the wall of a cave, the bottom of a floating island). That
 * costs extra density evaluations per block; the {@code MARGIN} check
 * below skips them for blocks whose density is nowhere near the
 * threshold (deep interior mass), which is the overwhelming majority of
 * solid blocks in practice.
 */
public final class Density3DSampler implements GroundHeightSource {

    /** density values further than this from the threshold skip neighbor sampling entirely -> DEEP. */
    private static final double MARGIN = 0.15;

    public enum BlockClass { AIR, SURFACE, SUBSURFACE, DEEP }

    private final DimensionPreset preset;
    private final NoiseUtil noise;
    private final List<DimensionPreset.Band> bands;

    public Density3DSampler(DimensionPreset preset, NoiseUtil noise) {
        this.preset = preset;
        this.noise = noise;
        if (!preset.terrain.density3d.bands.isEmpty()) {
            this.bands = preset.terrain.density3d.bands;
        } else {
            DimensionPreset.Band implicit = new DimensionPreset.Band();
            implicit.center = preset.terrain.baseHeight;
            implicit.thickness = Math.max(4, preset.terrain.heightVariation);
            this.bands = List.of(implicit);
        }
    }

    public double density(int x, int y, int z) {
        DimensionPreset.Noise n = preset.terrain.noise;
        double raw = noise.fbm3D(x, y, z, n.frequency, n.octaves, n.lacunarity, n.gain);

        double minPenalty = Double.MAX_VALUE;
        for (DimensionPreset.Band band : bands) {
            double dist = Math.abs(y - band.center);
            double t = dist / Math.max(1.0, band.thickness);
            double penalty = t * t * preset.terrain.density3d.verticalFalloff * band.thickness;
            if (penalty < minPenalty) {
                minPenalty = penalty;
            }
        }
        double value = raw - minPenalty;

        if (preset.terrain.density3d.shape.equalsIgnoreCase("spires")) {
            value += spireBonus(x, z);
        }
        return value;
    }

    /**
     * Extra density added near a 2D-cellular-noise "spire center," fading
     * with horizontal distance from it — see {@link DimensionPreset.Density3D#shape}'s
     * javadoc for the numerically-tuned defaults and what they produce.
     * Deliberately reuses the existing public {@link NoiseUtil#worley2D}
     * rather than tracking per-spire metadata (height, radius) separately;
     * the natural irregularity of the jittered cell centers plus the 3D
     * fBm already sampled into {@code raw} above is enough to make every
     * spire look distinct without needing that extra bookkeeping.
     */
    private double spireBonus(int x, int z) {
        DimensionPreset.Density3D d3 = preset.terrain.density3d;
        double dist = noise.worley2D(x, z, d3.spireFrequency, d3.spireJitter);
        double core = Math.max(0.0, 1.0 - dist / Math.max(0.01, d3.spireCoreFraction));
        return d3.spireStrength * core * core;
    }

    public boolean isSolid(int x, int y, int z) {
        return density(x, y, z) > preset.terrain.density3d.threshold;
    }

    public BlockClass classify(int x, int y, int z, int subsurfaceDepth) {
        double d = density(x, y, z);
        double threshold = preset.terrain.density3d.threshold;
        if (d <= threshold) {
            return BlockClass.AIR;
        }
        if (Math.abs(d - threshold) > MARGIN) {
            return BlockClass.DEEP; // far from any face, not worth the neighbor checks
        }
        if (!isSolid(x + 1, y, z) || !isSolid(x - 1, y, z) || !isSolid(x, y + 1, z)
                || !isSolid(x, y - 1, z) || !isSolid(x, y, z + 1) || !isSolid(x, y, z - 1)) {
            return BlockClass.SURFACE;
        }
        int r = Math.max(1, subsurfaceDepth);
        if (!isSolid(x + r, y, z) || !isSolid(x - r, y, z) || !isSolid(x, y + r, z)
                || !isSolid(x, y - r, z) || !isSolid(x, y, z + r) || !isSolid(x, y, z - r)) {
            return BlockClass.SUBSURFACE;
        }
        return BlockClass.DEEP;
    }

    @Override
    public int groundHeight(int worldX, int worldZ, int minY, int maxY) {
        for (int y = maxY - 1; y >= minY; y--) {
            if (isSolid(worldX, y, worldZ)) {
                return y;
            }
        }
        return minY;
    }
}
