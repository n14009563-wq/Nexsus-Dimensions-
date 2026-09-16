package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.noise.NoiseUtil;

/**
 * The single source of truth for "how tall is the terrain at this world
 * column", shared by {@link NexusChunkGenerator} (which paints it) and
 * {@link GiantTreePopulator} (which needs to know where the ground is to
 * root a tree). Keeping this in one place means the two can never drift
 * out of sync with each other.
 */
public final class TerrainHeightSampler implements GroundHeightSource {

    private final DimensionPreset preset;
    private final NoiseUtil noise;

    public TerrainHeightSampler(DimensionPreset preset, NoiseUtil noise) {
        this.preset = preset;
        this.noise = noise;
    }

    @Override
    public int groundHeight(int worldX, int worldZ, int minY, int maxY) {
        return columnHeight(worldX, worldZ, minY, maxY);
    }

    public int columnHeight(int worldX, int worldZ, int minY, int maxY) {
        DimensionPreset.Terrain t = preset.terrain;
        DimensionPreset.Noise n = t.noise;

        double base = noise.fbm2D(worldX, worldZ, n.frequency, n.octaves, n.lacunarity, n.gain, n.ridged, n.warp);
        int columnHeight = t.baseHeight + (int) Math.round(base * t.heightVariation);

        if (t.craters.enabled) {
            double crater = noise.worley2D(worldX, worldZ, t.craters.frequency, t.craters.jitter);
            if (crater < 0.35) {
                double bowl = 1.0 - (crater / 0.35);
                columnHeight -= (int) Math.round(bowl * t.craters.depth);
            } else if (crater < 0.5) {
                double rim = 1.0 - ((crater - 0.35) / 0.15);
                columnHeight += (int) Math.round(rim * t.craters.rimHeight);
            }
        }

        return Math.max(minY + 1, Math.min(maxY - 1, columnHeight));
    }
}
