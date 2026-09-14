package com.nexus.dimensions.generation;

/**
 * "Where's the ground at this column" abstraction, implemented once for
 * heightmap terrain ({@link TerrainHeightSampler}) and once for 3D
 * density terrain ({@link Density3DSampler}). {@link GiantTreePopulator}
 * depends on this instead of either concrete class, so the same tree
 * code roots correctly whether it's growing out of flat ground or off
 * the top of a floating island.
 */
public interface GroundHeightSource {
    /** Topmost solid Y at (worldX, worldZ) within [minY, maxY), or minY if the column is entirely air. */
    int groundHeight(int worldX, int worldZ, int minY, int maxY);
}
