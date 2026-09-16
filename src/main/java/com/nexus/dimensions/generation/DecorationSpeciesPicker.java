package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;

import java.util.List;

/**
 * Weighted, deterministic species pick for one decoration placement
 * attempt — same approach as {@link TreeSpeciesPicker}, but keyed by
 * {@code attempt} as well as chunk coordinates, since a single chunk can
 * host several independent decoration attempts (unlike the one-anchor-
 * per-chunk approach trees/structures use). Salt channel 900+, distinct
 * per attempt (see {@code DecorationPopulator}), so attempt N's species
 * roll never correlates with attempt N's own position roll or any other
 * attempt's rolls.
 */
public final class DecorationSpeciesPicker {

    private DecorationSpeciesPicker() {
    }

    public static DimensionPreset.DecorationSpecies pick(List<DimensionPreset.DecorationSpecies> species,
                                                           long seed, int chunkX, int chunkZ, int attempt) {
        double totalWeight = 0;
        for (DimensionPreset.DecorationSpecies s : species) {
            totalWeight += Math.max(0, s.weight);
        }
        if (totalWeight <= 0) {
            return species.get(0); // PresetLoader already warns and drops the whole list if every entry is <= 0
        }

        int salt = 900 + attempt * 10 + 4;
        double roll = DeterministicHash.hash01(seed, chunkX, chunkZ, salt) * totalWeight;
        double cumulative = 0;
        for (DimensionPreset.DecorationSpecies s : species) {
            cumulative += Math.max(0, s.weight);
            if (roll < cumulative) {
                return s;
            }
        }
        return species.get(species.size() - 1); // floating-point rounding fallback
    }
}
