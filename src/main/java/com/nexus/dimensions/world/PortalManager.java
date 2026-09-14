package com.nexus.dimensions.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Real Nether-portal blocks (built and lit with obsidian + flint and
 * steel, exactly as vanilla intends) re-purposed to link any two Nexus
 * dimensions instead of Overworld/Nether — see DESIGN.md section 10 for
 * why this reuses vanilla portal blocks rather than inventing a new
 * "frame" system: it's free animation, ambient sound, and particles, with
 * zero resource pack, for exactly the "portal" feeling this needs.
 * <p>
 * A portal is registered with {@code /nexusdim portal link}, which flood-
 * fills the connected {@code NETHER_PORTAL} blocks near the player into a
 * bounding box and persists it to {@code portals.yml}. {@link
 * PortalListener} matches a player's {@code PlayerPortalEvent} location
 * against these boxes and redirects the destination.
 */
public final class PortalManager {

    public record Portal(UUID id, String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                          String destWorldName, Double destX, Double destY, Double destZ) {

        public boolean contains(String worldName, int x, int y, int z) {
            return this.worldName.equals(worldName)
                    && x >= minX - 1 && x <= maxX + 1
                    && y >= minY - 1 && y <= maxY + 1
                    && z >= minZ - 1 && z <= maxZ + 1;
        }
    }

    private static final int MAX_FLOOD_FILL_BLOCKS = 4096; // sanity cap, a real portal is a few dozen blocks

    private final Logger logger;
    private final File portalsFile;
    private final List<Portal> portals = new ArrayList<>();

    public PortalManager(Plugin plugin) {
        this.logger = plugin.getLogger();
        this.portalsFile = new File(plugin.getDataFolder(), "portals.yml");
        load();
    }

    public List<Portal> list() {
        return List.copyOf(portals);
    }

    public Portal findContaining(String worldName, Location loc) {
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        for (Portal p : portals) {
            if (p.contains(worldName, x, y, z)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Flood-fills the connected NETHER_PORTAL blocks starting near {@code near}
     * (searches a small radius first since the player is standing near/in the
     * portal, not necessarily exactly on a portal block) and registers the
     * result. Returns null if no portal block was found nearby.
     */
    public Portal linkNearby(Location near, String destWorldName, Location destLoc) {
        Block start = findNearbyPortalBlock(near, 3);
        if (start == null) {
            return null;
        }

        Set<Block> connected = floodFillPortalBlocks(start);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Block b : connected) {
            minX = Math.min(minX, b.getX());
            minY = Math.min(minY, b.getY());
            minZ = Math.min(minZ, b.getZ());
            maxX = Math.max(maxX, b.getX());
            maxY = Math.max(maxY, b.getY());
            maxZ = Math.max(maxZ, b.getZ());
        }

        Portal portal = new Portal(UUID.randomUUID(), near.getWorld().getName(), minX, minY, minZ, maxX, maxY, maxZ,
                destWorldName,
                destLoc != null ? destLoc.getX() : null,
                destLoc != null ? destLoc.getY() : null,
                destLoc != null ? destLoc.getZ() : null);
        portals.add(portal);
        save();
        return portal;
    }

    /** Removes whichever registered portal contains `near`, if any. Returns true if one was removed. */
    public boolean unlinkNearby(Location near) {
        Portal found = findContaining(near.getWorld().getName(), near);
        if (found == null) {
            return false;
        }
        portals.remove(found);
        save();
        return true;
    }

    public Location resolveDestination(Portal portal) {
        World world = Bukkit.getWorld(portal.destWorldName());
        if (world == null) {
            return null;
        }
        if (portal.destX() != null) {
            return new Location(world, portal.destX(), portal.destY(), portal.destZ());
        }
        return world.getSpawnLocation();
    }

    private Block findNearbyPortalBlock(Location center, int radius) {
        World world = center.getWorld();
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = world.getBlockAt(cx + dx, cy + dy, cz + dz);
                    if (b.getType() == Material.NETHER_PORTAL) {
                        return b;
                    }
                }
            }
        }
        return null;
    }

    private Set<Block> floodFillPortalBlocks(Block start) {
        Set<Block> visited = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty() && visited.size() < MAX_FLOOD_FILL_BLOCKS) {
            Block current = queue.poll();
            for (org.bukkit.block.BlockFace face : new org.bukkit.block.BlockFace[]{
                    org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                    org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST,
                    org.bukkit.block.BlockFace.UP, org.bukkit.block.BlockFace.DOWN}) {
                Block next = current.getRelative(face);
                if (next.getType() == Material.NETHER_PORTAL && !visited.contains(next)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        return visited;
    }

    private void load() {
        if (!portalsFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(portalsFile);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection s = yaml.getConfigurationSection(key);
            if (s == null) continue;
            try {
                UUID id = UUID.fromString(key);
                Double destX = s.contains("destX") ? s.getDouble("destX") : null;
                Double destY = s.contains("destY") ? s.getDouble("destY") : null;
                Double destZ = s.contains("destZ") ? s.getDouble("destZ") : null;
                portals.add(new Portal(id, s.getString("world"),
                        s.getInt("minX"), s.getInt("minY"), s.getInt("minZ"),
                        s.getInt("maxX"), s.getInt("maxY"), s.getInt("maxZ"),
                        s.getString("destWorld"), destX, destY, destZ));
            } catch (Exception e) {
                logger.warning("[NexusDimensions] Skipped malformed portal entry '" + key + "' in portals.yml: " + e.getMessage());
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Portal p : portals) {
            String key = p.id().toString();
            yaml.set(key + ".world", p.worldName());
            yaml.set(key + ".minX", p.minX());
            yaml.set(key + ".minY", p.minY());
            yaml.set(key + ".minZ", p.minZ());
            yaml.set(key + ".maxX", p.maxX());
            yaml.set(key + ".maxY", p.maxY());
            yaml.set(key + ".maxZ", p.maxZ());
            yaml.set(key + ".destWorld", p.destWorldName());
            if (p.destX() != null) {
                yaml.set(key + ".destX", p.destX());
                yaml.set(key + ".destY", p.destY());
                yaml.set(key + ".destZ", p.destZ());
            }
        }
        try {
            yaml.save(portalsFile);
        } catch (IOException e) {
            logger.severe("[NexusDimensions] Could not save portals.yml: " + e.getMessage());
        }
    }
}
