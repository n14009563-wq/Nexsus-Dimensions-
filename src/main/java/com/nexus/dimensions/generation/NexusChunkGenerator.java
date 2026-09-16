package com.nexus.dimensions.generation;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.noise.NoiseUtil;
import com.nexus.dimensions.structure.Blueprint;
import com.nexus.dimensions.world.StructureLootService;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fully custom terrain generator. Never delegates to vanilla noise — every
 * block placed here comes from the owning {@link DimensionPreset}'s
 * {@code terrain} / {@code palette} config, which is what lets one preset
 * produce cratered airless moons and another produce a near-total ocean
 * world from the same code path.
 * <p>
 * See DESIGN.md section 4 for the generation algorithm in prose.
 */
public final class NexusChunkGenerator extends ChunkGenerator {

    private final DimensionPreset preset;
    private final NoiseUtil noise;
    private final TerrainHeightSampler heightSampler;
    private final Density3DSampler density3DSampler;
    private final GroundHeightSource groundHeightSource;
    private final Blueprint blueprint;
    private final StructureLootService lootService;
    private final BlockData surface;
    private final BlockData subsurface;
    private final BlockData deep;
    private final BlockData liquid;
    private final int liquidLevel;
    /** Lazily-resolved BlockData for palette.variants / palette.glowDeposits block keys, keyed by the raw block string. */
    private final Map<String, BlockData> extraBlockCache = new HashMap<>();
    /**
     * Large, arbitrary per-index offsets applied to variant/deposit noise
     * sampling coordinates so a preset with multiple variants/deposits
     * doesn't get them all landing in the same places as each other, or
     * lining up with the terrain height noise itself (same "offset the
     * sample point" idea {@code NoiseUtil.fbm2D}'s domain warp already
     * uses, just applied per-feature here instead of per-warp-axis).
     */
    private static double featureOffset(int index) {
        return (index + 1) * 73856.0;
    }

    public NexusChunkGenerator(DimensionPreset preset, long worldSeed, Map<String, Blueprint> blueprints,
                                StructureLootService lootService) {
        this.preset = preset;
        this.blueprint = preset.structures.enabled ? blueprints.get(preset.structures.blueprint) : null;
        this.lootService = lootService;
        if (preset.structures.enabled && this.blueprint == null) {
            // PresetLoader already disables structures if no blueprint name was set at all;
            // this covers the remaining case - a name that was set but matches no loaded file.
            org.bukkit.Bukkit.getLogger().warning("[NexusDimensions] Preset '" + preset.id
                    + "': structures.blueprint '" + preset.structures.blueprint
                    + "' doesn't match any loaded blueprints/*.yml file — structures disabled for this dimension.");
        }
        // Presets may pin an explicit seed independent of the world's own seed.
        this.noise = new NoiseUtil(preset.seed != null ? preset.seed : worldSeed);
        if (preset.terrain.isDensity3D()) {
            this.density3DSampler = new Density3DSampler(preset, noise);
            this.heightSampler = null;
            this.groundHeightSource = density3DSampler;
        } else {
            this.heightSampler = new TerrainHeightSampler(preset, noise);
            this.density3DSampler = null;
            this.groundHeightSource = heightSampler;
        }
        this.surface = materialOf(preset.palette.surfaceBlock).createBlockData();
        this.subsurface = materialOf(preset.palette.subsurfaceBlock).createBlockData();
        this.deep = materialOf(preset.palette.deepBlock).createBlockData();
        this.liquid = materialOf(preset.palette.liquidBlock).createBlockData();
        this.liquidLevel = preset.palette.liquidLevel >= 0 ? preset.palette.liquidLevel : preset.terrain.seaLevel;
    }

    private static Material materialOf(String key) {
        Material m = Material.matchMaterial(key);
        return m != null ? m : Material.STONE;
    }

    @Override
    public boolean shouldGenerateNoise() {
        return true;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false; // we paint surface/subsurface/deep ourselves inside generateNoise
    }

    @Override
    public boolean shouldGenerateBedrock() {
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false; // custom cave carving happens inside generateNoise when enabled
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return preset.flavor.generateDecorations;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return preset.flavor.generateStructures;
    }

    @Override
    public boolean isParallelCapable() {
        // NoiseUtil's permutation table is read-only after construction and
        // this class holds no other mutable state, so chunk columns can be
        // computed concurrently.
        return true;
    }

    @Override
    public void generateNoise(WorldInfo worldInfo, Random random, int chunkX, int chunkZ, ChunkData chunkData) {
        if (preset.terrain.isDensity3D()) {
            generateDensity3D(chunkX, chunkZ, chunkData);
        } else {
            generateHeightmap(chunkX, chunkZ, chunkData);
        }
    }

    /** True 3D terrain: overhangs, arches, floating islands. See Density3DSampler. */
    private void generateDensity3D(int chunkX, int chunkZ, ChunkData chunkData) {
        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        int subsurfaceDepth = preset.palette.subsurfaceDepth;

        for (int x = 0; x < 16; x++) {
            int worldX = (chunkX << 4) + x;
            for (int z = 0; z < 16; z++) {
                int worldZ = (chunkZ << 4) + z;
                BlockData columnSurface = resolveVariant(worldX, worldZ, true);
                BlockData columnSubsurface = resolveVariant(worldX, worldZ, false);
                for (int y = minY; y < maxY; y++) {
                    Density3DSampler.BlockClass cls = density3DSampler.classify(worldX, y, worldZ, subsurfaceDepth);
                    switch (cls) {
                        case SURFACE -> chunkData.setBlock(x, y, z, columnSurface != null ? columnSurface : surface);
                        case SUBSURFACE -> chunkData.setBlock(x, y, z, columnSubsurface != null ? columnSubsurface : subsurface);
                        case DEEP -> {
                            BlockData deposit = resolveGlowDeposit(worldX, y, worldZ);
                            chunkData.setBlock(x, y, z, deposit != null ? deposit : deep);
                        }
                        case AIR -> {
                            // Opt-in, flat-Y-threshold liquid fill - not a flood fill, see
                            // DimensionPreset.Density3D#liquids and DESIGN.md section 4c.
                            if (preset.terrain.density3d.liquids && y <= liquidLevel) {
                                chunkData.setBlock(x, y, z, liquid);
                            }
                        }
                    }
                }
            }
        }
    }

    private void generateHeightmap(int chunkX, int chunkZ, ChunkData chunkData) {
        int minY = chunkData.getMinHeight();
        int maxY = chunkData.getMaxHeight();
        DimensionPreset.Terrain t = preset.terrain;
        boolean cellularCaves = t.caves.mode.equalsIgnoreCase("cellular");

        for (int x = 0; x < 16; x++) {
            int worldX = (chunkX << 4) + x;
            for (int z = 0; z < 16; z++) {
                int worldZ = (chunkZ << 4) + z;

                int columnHeight = heightSampler.columnHeight(worldX, worldZ, minY, maxY);
                BlockData columnSurface = resolveVariant(worldX, worldZ, true);
                BlockData columnSubsurface = resolveVariant(worldX, worldZ, false);

                for (int y = minY; y < maxY; y++) {
                    if (y > columnHeight) {
                        if (y <= liquidLevel) {
                            chunkData.setBlock(x, y, z, liquid);
                        }
                        continue;
                    }

                    boolean carved = false;
                    if (t.caves.enabled && y > minY + 5 && y < columnHeight - 2) {
                        if (cellularCaves) {
                            double cell = noise.worley3D(worldX, y, worldZ, t.caves.frequency, t.caves.cellularJitter);
                            carved = cell < t.caves.cellularThreshold;
                        } else {
                            double cave = noise.fbm3D(worldX, y, worldZ, t.caves.frequency, 3, 2.0, 0.5);
                            carved = cave > t.caves.threshold;
                        }
                    }
                    if (carved) {
                        continue; // leave as air
                    }

                    if (y == columnHeight) {
                        BlockData block = y <= liquidLevel ? columnSubsurface : columnSurface;
                        chunkData.setBlock(x, y, z, block != null ? block : (y <= liquidLevel ? subsurface : surface));
                    } else if (y > columnHeight - preset.palette.subsurfaceDepth) {
                        chunkData.setBlock(x, y, z, columnSubsurface != null ? columnSubsurface : subsurface);
                    } else {
                        BlockData deposit = resolveGlowDeposit(worldX, y, worldZ);
                        chunkData.setBlock(x, y, z, deposit != null ? deposit : deep);
                    }
                }
            }
        }
    }

    /**
     * First matching {@code palette.variants} entry for this column, or
     * null to keep the primary surface/subsurface block. Evaluated once
     * per (x, z) column (variants don't vary with height), unlike glow
     * deposits below which are genuinely 3D. See DESIGN.md section 4c.
     */
    private BlockData resolveVariant(int worldX, int worldZ, boolean surfaceLayer) {
        List<DimensionPreset.PaletteVariant> variants = preset.palette.variants;
        for (int i = 0; i < variants.size(); i++) {
            DimensionPreset.PaletteVariant v = variants.get(i);
            String blockKey = surfaceLayer ? v.surfaceBlock : v.subsurfaceBlock;
            if (blockKey == null) {
                continue;
            }
            double offset = featureOffset(i);
            double sample = noise.fbm2D(worldX + offset, worldZ + offset, v.frequency, 3, 2.0, 0.5, false, 0.0);
            if (sample > v.threshold) {
                return extraBlockCache.computeIfAbsent(blockKey, k -> materialOf(k).createBlockData());
            }
        }
        return null;
    }

    /** First matching {@code palette.glowDeposits} entry for this block, or null. See DESIGN.md section 4c. */
    private BlockData resolveGlowDeposit(int worldX, int y, int worldZ) {
        List<DimensionPreset.GlowDeposit> deposits = preset.palette.glowDeposits;
        for (int i = 0; i < deposits.size(); i++) {
            DimensionPreset.GlowDeposit dep = deposits.get(i);
            double offset = featureOffset(i);
            double sample = noise.fbm3D(worldX + offset, y + offset, worldZ + offset, dep.frequency, 2, 2.0, 0.5);
            if (sample > dep.threshold) {
                return extraBlockCache.computeIfAbsent(dep.block, k -> materialOf(k).createBlockData());
            }
        }
        return null;
    }

    @Override
    public BiomeProvider getDefaultBiomeProvider(WorldInfo worldInfo) {
        return new NexusBiomeProvider(preset);
    }

    @Override
    public List<BlockPopulator> getDefaultPopulators(World world) {
        List<BlockPopulator> populators = new ArrayList<>();
        if (preset.trees.enabled) {
            populators.add(new GiantTreePopulator(preset, groundHeightSource, noise));
        }
        if (preset.decorations.enabled && !preset.decorations.species.isEmpty()) {
            populators.add(new DecorationPopulator(preset, groundHeightSource));
        }
        if (preset.structures.enabled && blueprint != null) {
            populators.add(new StructurePopulator(preset, blueprint, groundHeightSource, lootService));
        }
        return populators;
    }
}
