package com.nexus.dimensions.world;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.generation.DeterministicHash;
import com.nexus.dimensions.structure.Blueprint;
import com.nexus.dimensions.structure.BlueprintTransform;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Tier 2 counterpart to {@code StructurePopulator}, same relationship as
 * {@link NexusFloraListener} is to {@code GiantTreePopulator}: Tier 2
 * worlds never hand us a {@code ChunkGenerator}, so this places the same
 * blueprints directly through the live {@link World} on {@link
 * ChunkLoadEvent} instead. Runs on the main thread already (unlike the
 * Tier 1 populator, which may run off-thread), but still routes loot
 * through {@link StructureLootService} rather than duplicating that logic
 * here — one place that knows how to turn a chest into a real vanilla
 * loot table.
 */
public final class NexusStructureListener implements Listener {

    private final DimensionManager dimensionManager;
    private final StructureLootService lootService;

    public NexusStructureListener(Plugin plugin, DimensionManager dimensionManager, StructureLootService lootService) {
        this.dimensionManager = dimensionManager;
        this.lootService = lootService;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) {
            return;
        }
        World world = event.getWorld();
        DimensionPreset preset = dimensionManager.getPresetForWorld(world.getName());
        if (preset == null || !preset.isTier2() || !preset.structures.enabled) {
            return;
        }
        Blueprint blueprint = dimensionManager.getBlueprint(preset.structures.blueprint);
        if (blueprint == null || blueprint.blocks.isEmpty()) {
            return;
        }

        int chunkX = event.getChunk().getX();
        int chunkZ = event.getChunk().getZ();
        // Same salt channels as StructurePopulator (20-22) so a preset that somehow
        // runs both paths (it won't, tiers are mutually exclusive per world) would
        // still pick the same anchor - mostly just keeping the convention consistent.
        if (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 20) > preset.structures.rarityPerChunk) {
            return;
        }

        int localX = (int) (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 21) * 16);
        int localZ = (int) (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 22) * 16);
        int worldX = (chunkX << 4) + localX;
        int worldZ = (chunkZ << 4) + localZ;

        int groundY = world.getHighestBlockYAt(worldX, worldZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        // Same salt channels (23-24) as StructurePopulator - see its comment.
        int rotationStep = preset.structures.randomRotation
                ? (int) (DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 23) * 4)
                : 0;
        boolean mirror = preset.structures.randomMirror
                && DeterministicHash.hash01(world.getSeed(), chunkX, chunkZ, 24) < 0.5;

        for (Blueprint.BlockEntry entry : blueprint.blocks) {
            int[] transformed = BlueprintTransform.apply(entry.dx, entry.dz, rotationStep, mirror);
            int x = worldX + transformed[0];
            int y = groundY + 1 + entry.dy;
            int z = worldZ + transformed[1];
            if (y < minY || y >= maxY) {
                continue;
            }
            if (entry.loot) {
                world.getBlockAt(x, y, z).setType(Material.CHEST, false);
                lootService.enqueue(world.getName(), x, y, z, preset.structures.lootTable);
            } else {
                world.getBlockAt(x, y, z).setBlockData(materialOf(entry.block).createBlockData(), false);
            }
        }
    }

    private static Material materialOf(String key) {
        Material m = Material.matchMaterial(key);
        return m != null ? m : Material.STONE;
    }
}
