package com.nexus.dimensions.command;

import com.nexus.dimensions.config.DimensionPreset;
import com.nexus.dimensions.config.PresetLoader;
import com.nexus.dimensions.datapack.DatapackGenerator;
import com.nexus.dimensions.structure.BlueprintLoader;
import com.nexus.dimensions.world.DimensionManager;
import com.nexus.dimensions.world.PortalManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class NexusDimCommand implements CommandExecutor, TabCompleter {

    private final PresetLoader presetLoader;
    private final DimensionManager dimensionManager;
    private final DatapackGenerator datapackGenerator;
    private final BlueprintLoader blueprintLoader;
    private final PortalManager portalManager;

    public NexusDimCommand(PresetLoader presetLoader, DimensionManager dimensionManager, DatapackGenerator datapackGenerator,
                            BlueprintLoader blueprintLoader, PortalManager portalManager) {
        this.presetLoader = presetLoader;
        this.dimensionManager = dimensionManager;
        this.datapackGenerator = datapackGenerator;
        this.blueprintLoader = blueprintLoader;
        this.portalManager = portalManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(usage());
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "list" -> handleList(sender);
            case "create" -> handleCreate(sender, args);
            case "tp" -> handleTp(sender, args);
            case "reload" -> handleReload(sender);
            case "portal" -> handlePortal(sender, args);
            default -> sender.sendMessage(usage());
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        Map<String, DimensionPreset> presets = dimensionManager.getPresets();
        if (presets.isEmpty()) {
            sender.sendMessage(Component.text("No presets loaded. Add a .yml file to the presets/ folder and run /nexusdim reload.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Nexus presets:", NamedTextColor.GOLD));
        for (DimensionPreset preset : presets.values()) {
            String tier = preset.isTier2() ? "Tier 2 (restart)" : "Tier 1 (instant)";
            sender.sendMessage(Component.text(" - " + preset.id + "  [" + tier + "]  \"" + preset.displayName + "\"",
                    NamedTextColor.AQUA));
        }
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /nexusdim create <worldName> <presetId> [seed]", NamedTextColor.RED));
            return;
        }
        String worldName = args[1];
        String presetId = args[2];
        Long seed = null;
        if (args.length >= 4) {
            try {
                seed = Long.parseLong(args[3]);
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Seed must be a whole number.", NamedTextColor.RED));
                return;
            }
        }

        DimensionPreset preset = dimensionManager.getPresets().get(presetId);
        DimensionManager.CreateResult result = dimensionManager.createOrLoad(worldName, presetId, seed);

        switch (result) {
            case UNKNOWN_PRESET -> sender.sendMessage(Component.text("No preset named '" + presetId + "'. Try /nexusdim list.", NamedTextColor.RED));
            case NAME_COLLISION -> sender.sendMessage(Component.text("A world named '" + worldName + "' already exists and isn't managed by Nexus Dimensions.", NamedTextColor.RED));
            case TIER1_ALREADY_LOADED -> sender.sendMessage(Component.text("'" + worldName + "' is already loaded.", NamedTextColor.YELLOW));
            case TIER1_CREATED -> {
                sender.sendMessage(Component.text("Created dimension '" + worldName + "' from preset '" + presetId + "'.", NamedTextColor.GREEN));
                teleportIfPlayer(sender, worldName);
            }
            case TIER1_CREATED_ON_PREEXISTING_FOLDER -> {
                sender.sendMessage(Component.text("Loaded '" + worldName + "' with preset '" + presetId + "', BUT that world folder already had "
                        + "saved chunks on disk before this. Those already-explored chunks keep their original terrain — only newly generated "
                        + "chunks from here on use this preset. If you wanted a completely fresh dimension, stop the server, delete that world "
                        + "folder, and re-run this with the same name.", NamedTextColor.RED));
                teleportIfPlayer(sender, worldName);
            }
            case TIER2_ALREADY_ACTIVE -> sender.sendMessage(Component.text("Preset '" + presetId + "' is already an active Tier 2 dimension.", NamedTextColor.YELLOW));
            case TIER2_DATAPACK_WRITTEN_RESTART_REQUIRED -> {
                boolean written = preset != null && datapackGenerator.writeDatapack(preset);
                if (written) {
                    sender.sendMessage(Component.text("Preset '" + presetId + "' needs a custom world height/sky, so it's Tier 2. "
                            + "Datapack written — restart the server to activate it.", NamedTextColor.GOLD));
                } else {
                    sender.sendMessage(Component.text("Failed to write the datapack for '" + presetId + "' — check the server console.", NamedTextColor.RED));
                }
            }
        }
    }

    private void teleportIfPlayer(CommandSender sender, String worldName) {
        if (sender instanceof Player player) {
            World world = Bukkit.getWorld(worldName);
            if (world != null) {
                player.teleport(world.getSpawnLocation());
            }
        }
    }

    private void handleTp(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can teleport.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("Usage: /nexusdim tp <worldName>", NamedTextColor.RED));
            return;
        }
        World world = Bukkit.getWorld(args[1]);
        if (world == null) {
            sender.sendMessage(Component.text("No loaded world named '" + args[1] + "'.", NamedTextColor.RED));
            return;
        }
        player.teleport(world.getSpawnLocation());
        sender.sendMessage(Component.text("Teleported to '" + args[1] + "'.", NamedTextColor.GREEN));
    }

    private void handleReload(CommandSender sender) {
        Map<String, DimensionPreset> reloaded = presetLoader.loadAll();
        dimensionManager.reloadPresets(reloaded);
        dimensionManager.reloadBlueprints(blueprintLoader.loadAll());
        sender.sendMessage(Component.text("Reloaded " + reloaded.size() + " preset(s) and blueprint(s) from disk.", NamedTextColor.GREEN));

        long tier2Count = reloaded.values().stream().filter(DimensionPreset::isTier2).count();
        if (tier2Count > 0) {
            sender.sendMessage(Component.text("Refreshing datapacks for " + tier2Count + " Tier 2 preset(s)...", NamedTextColor.GOLD));
            for (DimensionPreset preset : reloaded.values()) {
                if (preset.isTier2()) {
                    datapackGenerator.writeDatapack(preset);
                }
            }
            sender.sendMessage(Component.text("Datapacks refreshed. New/changed Tier 2 dimensions need a server restart.", NamedTextColor.GOLD));
        }
    }

    private void handlePortal(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("Only players can manage portals - stand in the portal you mean.", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(portalUsage());
            return;
        }

        switch (args[1].toLowerCase()) {
            case "link" -> handlePortalLink(player, args);
            case "unlink" -> handlePortalUnlink(player);
            case "list" -> handlePortalList(sender);
            default -> sender.sendMessage(portalUsage());
        }
    }

    private void handlePortalLink(Player player, String[] args) {
        // Optional trailing "both" also tries to auto-link a return portal on the
        // destination side - stripped off before the existing arg-position parsing
        // below so it doesn't disturb the [destX destY destZ] slots. See DESIGN.md
        // section 10 and attemptAutoReturnLink's javadoc for what "auto" means here.
        boolean bothWays = args.length >= 4 && args[args.length - 1].equalsIgnoreCase("both");
        String[] a = bothWays ? java.util.Arrays.copyOf(args, args.length - 1) : args;

        if (a.length < 3) {
            player.sendMessage(Component.text("Usage: /nexusdim portal link <destinationWorld> [destX destY destZ] [both]", NamedTextColor.RED));
            return;
        }
        String destWorldName = a[2];
        World destWorld = Bukkit.getWorld(destWorldName);
        if (destWorld == null) {
            player.sendMessage(Component.text("No loaded world named '" + destWorldName + "'. It must be loaded to link to it.", NamedTextColor.RED));
            return;
        }

        Location destLoc = null;
        if (a.length >= 6) {
            try {
                double x = Double.parseDouble(a[3]);
                double y = Double.parseDouble(a[4]);
                double z = Double.parseDouble(a[5]);
                destLoc = new Location(destWorld, x, y, z);
            } catch (NumberFormatException e) {
                player.sendMessage(Component.text("destX/destY/destZ must be numbers.", NamedTextColor.RED));
                return;
            }
        }

        PortalManager.Portal portal = portalManager.linkNearby(player.getLocation(), destWorldName, destLoc);
        if (portal == null) {
            player.sendMessage(Component.text("No lit Nether portal found within 3 blocks of you. Build and light one first "
                    + "(obsidian frame + flint and steel), then stand in it and run this again.", NamedTextColor.RED));
            return;
        }
        String destDesc = destLoc != null
                ? String.format("%.1f, %.1f, %.1f in '%s'", destLoc.getX(), destLoc.getY(), destLoc.getZ(), destWorldName)
                : "'" + destWorldName + "'s spawn";
        player.sendMessage(Component.text("Linked this portal to " + destDesc + ".", NamedTextColor.GREEN));

        if (bothWays) {
            boolean linkedBack = attemptAutoReturnLink(player, portal, destWorld, destLoc);
            if (linkedBack) {
                player.sendMessage(Component.text("Also found a lit portal near the destination and linked it back to this one.", NamedTextColor.GREEN));
            } else {
                player.sendMessage(Component.text("Couldn't find a lit portal within a few blocks of the destination to auto-link back "
                        + "- build one there, stand in it, and run '/nexusdim portal link " + player.getWorld().getName()
                        + "' from that side.", NamedTextColor.YELLOW));
            }
        }
    }

    /**
     * Best-effort "also link the return trip" for {@code portal link ... both}:
     * searches a small radius (same {@link PortalManager#linkNearby} search this
     * command already uses for the forward link) around the destination - the
     * given coordinates, or that world's spawn if none were given - for an
     * existing lit portal, and if one's found, registers it pointing back at the
     * portal that was just created here. Deliberately not a "create one if
     * missing" flow (this plugin doesn't place blocks in a player's world
     * unprompted) - if nothing's found nearby, {@link #handlePortalLink} tells the
     * player to build one and link it manually, same as before this flag existed.
     * See DESIGN.md section 10.
     */
    private boolean attemptAutoReturnLink(Player player, PortalManager.Portal forward, World destWorld, Location destLoc) {
        Location searchNear = destLoc != null ? destLoc : destWorld.getSpawnLocation();
        double centerX = (forward.minX() + forward.maxX()) / 2.0 + 0.5;
        double centerZ = (forward.minZ() + forward.maxZ()) / 2.0 + 0.5;
        Location sourceReturn = new Location(player.getWorld(), centerX, forward.minY(), centerZ);
        PortalManager.Portal back = portalManager.linkNearby(searchNear, player.getWorld().getName(), sourceReturn);
        return back != null;
    }

    private void handlePortalUnlink(Player player) {
        boolean removed = portalManager.unlinkNearby(player.getLocation());
        if (removed) {
            player.sendMessage(Component.text("Unlinked the portal you're standing near. The physical portal blocks are untouched.", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("No registered Nexus portal found near you.", NamedTextColor.YELLOW));
        }
    }

    private void handlePortalList(CommandSender sender) {
        List<PortalManager.Portal> portals = portalManager.list();
        if (portals.isEmpty()) {
            sender.sendMessage(Component.text("No portals registered yet. Stand in a lit portal and run /nexusdim portal link.", NamedTextColor.YELLOW));
            return;
        }
        sender.sendMessage(Component.text("Registered portals:", NamedTextColor.GOLD));
        for (PortalManager.Portal p : portals) {
            sender.sendMessage(Component.text(" - " + p.worldName() + " [" + p.minX() + "," + p.minY() + "," + p.minZ()
                    + "] -> " + p.destWorldName(), NamedTextColor.AQUA));
        }
    }

    private Component usage() {
        return Component.text("Usage: /nexusdim <list|create|tp|reload|portal>", NamedTextColor.YELLOW);
    }

    private Component portalUsage() {
        return Component.text("Usage: /nexusdim portal <link|unlink|list>", NamedTextColor.YELLOW);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("list", "create", "tp", "reload", "portal"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("tp")) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return filter(new ArrayList<>(dimensionManager.getPresets().keySet()), args[2]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("portal")) {
            return filter(List.of("link", "unlink", "list"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("portal") && args[1].equalsIgnoreCase("link")) {
            return filter(Bukkit.getWorlds().stream().map(World::getName).collect(Collectors.toList()), args[2]);
        }
        // "both" can follow either <destinationWorld> directly or the [destX destY destZ] triple.
        if (args.length == 4 && args[0].equalsIgnoreCase("portal") && args[1].equalsIgnoreCase("link")) {
            return filter(List.of("both"), args[3]);
        }
        if (args.length == 7 && args[0].equalsIgnoreCase("portal") && args[1].equalsIgnoreCase("link")) {
            return filter(List.of("both"), args[6]);
        }
        return List.of();
    }

    private List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).collect(Collectors.toList());
    }
}
