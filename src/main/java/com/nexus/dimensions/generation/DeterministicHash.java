package com.nexus.dimensions.generation;

/**
 * Shared per-chunk deterministic hash, used anywhere a chunk populator
 * needs to pick "is this chunk special" (a tree anchor, a structure
 * anchor, ...) reproducibly across restarts without persisting anything.
 * Same seed + same chunk + same salt channel always gives the same
 * [0, 1) value; different salts on the same chunk are independent.
 */
public final class DeterministicHash {

    private DeterministicHash() {
    }

    public static double hash01(long seed, int chunkX, int chunkZ, int salt) {
        long h = seed;
        h = h * 6364136223846793005L + chunkX * 1442695040888963407L;
        h = h * 6364136223846793005L + chunkZ * 1442695040888963407L;
        h = h * 6364136223846793005L + salt * 1442695040888963407L;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        return ((h & 0xFFFFFFFFFFFFFL) / (double) 0xFFFFFFFFFFFFFL);
    }
}
