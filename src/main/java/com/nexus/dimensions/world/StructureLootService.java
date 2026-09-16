package com.nexus.dimensions.world;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.loot.LootTable;
import org.bukkit.loot.LootTables;
import org.bukkit.loot.Lootable;
import org.bukkit.plugin.Plugin;

import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;

/**
 * Assigns vanilla loot tables to chests {@code StructurePopulator} places
 * — real {@code org.bukkit.loot.LootTables}, no custom loot code, same
 * "use what Minecraft already has" reasoning as the particle/gravity
 * systems.
 * <p>
 * This exists as its own service instead of being done directly inside
 * {@code StructurePopulator.populate()} because a {@link Lootable}
 * BlockState is real live-world API — reading/writing it is a main-thread
 * operation. Chunk population, on the other hand, may run off the main
 * thread (our generator declares {@code isParallelCapable() == true}) and
 * only has safe access to raw block data through {@code LimitedRegion}.
 * So the populator just enqueues "this world, this coordinate, this loot
 * table" — cheap, thread-safe, touches no Bukkit world state — and this
 * service drains that queue on a repeating main-thread task shortly after.
 */
public final class StructureLootService {

    private record PendingLoot(String worldName, int x, int y, int z, String lootTableKey) {
    }

    private final Logger logger;
    private final ConcurrentLinkedQueue<PendingLoot> pending = new ConcurrentLinkedQueue<>();

    public StructureLootService(Plugin plugin) {
        this.logger = plugin.getLogger();
        Bukkit.getScheduler().runTaskTimer(plugin, this::drain, 20L, 10L);
    }

    /** Safe to call from any thread, including off the main thread during chunk generation. */
    public void enqueue(String worldName, int x, int y, int z, String lootTableKey) {
        if (lootTableKey == null || lootTableKey.isBlank()) {
            return; // no loot table configured - chest stays empty, that's a valid choice
        }
        pending.add(new PendingLoot(worldName, x, y, z, lootTableKey));
    }

    private void drain() {
        PendingLoot item;
        while ((item = pending.poll()) != null) {
            apply(item);
        }
    }

    private void apply(PendingLoot item) {
        World world = Bukkit.getWorld(item.worldName());
        if (world == null) {
            logger.warning("[NexusDimensions] Dropped a pending loot assignment for unloaded world '"
                    + item.worldName() + "'.");
            return;
        }
        LootTable table = resolveLootTable(item.lootTableKey());
        if (table == null) {
            return; // already warned about the bad key when the preset loaded
        }
        Block block = world.getBlockAt(item.x(), item.y(), item.z());
        BlockState state = block.getState();
        if (state instanceof Lootable lootable) {
            lootable.setLootTable(table);
            state.update();
        } else {
            logger.warning("[NexusDimensions] Expected a lootable block at " + item.x() + "," + item.y() + ","
                    + item.z() + " in '" + item.worldName() + "' but found " + block.getType()
                    + " — the structure populator may have placed something other than a chest for a loot: true entry.");
        }
    }

    private LootTable resolveLootTable(String key) {
        try {
            LootTables table = LootTables.valueOf(key.trim().toUpperCase(Locale.ROOT));
            return table.getLootTable();
        } catch (IllegalArgumentException e) {
            logger.warning("[NexusDimensions] '" + key + "' isn't a known org.bukkit.loot.LootTables name.");
            return null;
        }
    }
}
