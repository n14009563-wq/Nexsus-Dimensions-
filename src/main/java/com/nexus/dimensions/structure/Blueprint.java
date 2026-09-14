package com.nexus.dimensions.structure;

import java.util.List;

/**
 * A small hand-authored structure, loaded from {@code blueprints/<name>.yml}.
 * Deliberately simple — a flat list of relative block offsets, not a
 * schematic/NBT import pipeline. Origin is one block above ground: {@code
 * dy: 0} places at {@code groundY + 1}, so a blueprint's floor layer is
 * usually {@code dy: 0} and everything above counts up from there.
 */
public final class Blueprint {
    public String name;
    public List<BlockEntry> blocks = List.of();

    public static final class BlockEntry {
        public int dx;
        public int dy;
        public int dz;
        public String block = "minecraft:stone";
        /** If true, this block is placed as a chest and queued for loot-table assignment (see StructureLootService). */
        public boolean loot = false;
    }
}
