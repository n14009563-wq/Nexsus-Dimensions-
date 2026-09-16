package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.noise.NoiseUtil;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves biomes for a preset. Works identically for vanilla biome keys
 * ({@code minecraft:plains}) and for custom datapack biomes ({@code
 * nexus:violet_canopy}) since both are just entries in {@link
 * Registry#BIOME} once the datapack has loaded — Nexus Dimensions never
 * needs to know the difference.
 */
public final class NexusBiomeProvider extends BiomeProvider {

    private final DimensionPreset preset;
    private final NoiseUtil blendNoise;
    private final List<Biome> resolvedBiomes;
    private final List<Double> cumulativeWeights;

    public NexusBiomeProvider(DimensionPreset preset) {
        this.preset = preset;
        long blendSeed = preset.seed != null ? preset.seed : preset.id.hashCode();
        this.blendNoise = new NoiseUtil(blendSeed ^ 0x5EEDB10DL);

        List<Biome> biomes = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double total = 0;
        for (DimensionPreset.BiomeEntry entry : preset.biomes.entries) {
            Biome biome = resolve(entry.id);
            biomes.add(biome);
            total += Math.max(0.0001, entry.weight);
            weights.add(total);
        }
        if (biomes.isEmpty()) {
            biomes.add(Biome.PLAINS);
            weights.add(1.0);
            total = 1.0;
        }
        this.resolvedBiomes = biomes;
        this.cumulativeWeights = weights;
    }

    private static Biome resolve(String id) {
        if (id == null) {
            return Biome.PLAINS;
        }
        NamespacedKey key = NamespacedKey.fromString(id.toLowerCase());
        if (key == null) {
            return Biome.PLAINS;
        }
        Biome biome = Registry.BIOME.get(key);
        return biome != null ? biome : Biome.PLAINS;
    }

    @Override
    public Biome getBiome(WorldInfo worldInfo, int x, int y, int z) {
        if (!"blended".equalsIgnoreCase(preset.biomes.mode) || resolvedBiomes.size() == 1) {
            return resolvedBiomes.get(0);
        }
        double n = (blendNoise.fbm2D(x, z, 0.004, 3, 2.0, 0.5, false, 0.0) + 1.0) / 2.0; // -> [0,1]
        double total = cumulativeWeights.get(cumulativeWeights.size() - 1);
        double target = n * total;
        for (int i = 0; i < cumulativeWeights.size(); i++) {
            if (target <= cumulativeWeights.get(i)) {
                return resolvedBiomes.get(i);
            }
        }
        return resolvedBiomes.get(resolvedBiomes.size() - 1);
    }

    @Override
    public List<Biome> getBiomes(WorldInfo worldInfo) {
        return resolvedBiomes;
    }
}
