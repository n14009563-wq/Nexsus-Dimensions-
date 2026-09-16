package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;

import java.util.List;

/**
 * Picks which {@link DimensionPreset.TreeSpecies} grows at a given tree
 * anchor chunk — weighted random, deterministic (salt 5, distinct from the
 * anchor/position/height rolls on salts 1-4 so species selection doesn't
 * correlate with where or how tall a tree is), so re-generating an
 * unexplored chunk always regrows the same species. Falls back to a single
 * implicit species built from {@link DimensionPreset.Trees}'s own fields
 * when {@code trees.species} wasn't configured, which is what keeps every
 * preset from before this feature existed behaving identically.
 */
public final class TreeSpeciesPicker {

    private TreeSpeciesPicker() {
    }

    public static DimensionPreset.TreeSpecies pick(DimensionPreset.Trees cfg, long seed, int chunkX, int chunkZ) {
        List<DimensionPreset.TreeSpecies> list = cfg.species;
        if (list.isEmpty()) {
            DimensionPreset.TreeSpecies fallback = new DimensionPreset.TreeSpecies();
            fallback.name = "default";
            fallback.minHeight = cfg.minHeight;
            fallback.maxHeight = cfg.maxHeight;
            fallback.canopyRadius = cfg.canopyRadius;
            fallback.trunkBlock = cfg.trunkBlock;
            fallback.leafBlock = cfg.leafBlock;
            fallback.giantCanopyLayers = cfg.giantCanopyLayers;
            fallback.branches = cfg.branches;
            fallback.buttressRoots = cfg.buttressRoots;
            fallback.canopyAccentBlock = cfg.canopyAccentBlock;
            fallback.canopyAccentChance = cfg.canopyAccentChance;
            fallback.trunkAccentBlock = cfg.trunkAccentBlock;
            fallback.trunkAccentChance = cfg.trunkAccentChance;
            fallback.vineBlock = cfg.vineBlock;
            fallback.vineChance = cfg.vineChance;
            fallback.vineMinLength = cfg.vineMinLength;
            fallback.vineMaxLength = cfg.vineMaxLength;
            return fallback;
        }

        double totalWeight = 0;
        for (DimensionPreset.TreeSpecies s : list) {
            totalWeight += Math.max(0, s.weight);
        }
        if (totalWeight <= 0) {
            return list.get(0); // PresetLoader already warns and falls back to single-species if this is the case for all entries
        }

        double roll = DeterministicHash.hash01(seed, chunkX, chunkZ, 5) * totalWeight;
        double cumulative = 0;
        for (DimensionPreset.TreeSpecies s : list) {
            cumulative += Math.max(0, s.weight);
            if (roll < cumulative) {
                return s;
            }
        }
        return list.get(list.size() - 1); // floating-point rounding fallback
    }
}
