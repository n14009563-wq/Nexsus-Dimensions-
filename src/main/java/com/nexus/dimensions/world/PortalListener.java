package com.nexus.dimensions.world;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.plugin.Plugin;

/**
 * Intercepts real vanilla Nether-portal travel and redirects it using
 * {@link PortalManager}'s registered links instead of vanilla's own
 * Overworld&lt;-&gt;Nether pairing logic. See {@link PortalManager} for why
 * this reuses real {@code NETHER_PORTAL} blocks rather than inventing a
 * custom "frame" system.
 * <p>
 * Uncertain API surface (see README "if anything fails to compile"):
 * {@link PlayerPortalEvent#setSearchRadius(int)}, {@code
 * setCreationRadius(int)}, and {@code setCanCreatePortal(boolean)} have
 * existed on Spigot/Paper's {@code PlayerPortalEvent} for a long time, but
 * this couldn't be checked against the real 1.21.x API jar in this
 * environment (no Maven network access - see README). If any one of the
 * three fails to compile, delete that line; the important one is {@code
 * setTo(...)}, which is stable across versions and is what actually
 * redirects the teleport. The other three are best-effort - they stop
 * vanilla from also searching for/carving a second, unlinked portal at the
 * destination alongside the one we've already supplied via {@link
 * PortalManager#resolveDestination}.
 */
public final class PortalListener implements Listener {

    private final PortalManager portalManager;

    public PortalListener(Plugin plugin, PortalManager portalManager) {
        this.portalManager = portalManager;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        PortalManager.Portal portal = portalManager.findContaining(event.getFrom().getWorld().getName(), event.getFrom());
        if (portal == null) {
            return; // not standing in a Nexus-registered portal - leave vanilla behavior alone
        }

        org.bukkit.Location destination = portalManager.resolveDestination(portal);
        if (destination == null) {
            // Registered, but the destination world isn't currently loaded (Tier 2 preset
            // not yet active, or the world was unloaded). Cancel rather than let vanilla
            // dump the player into a freshly-generated Nether/End of whatever world they're in.
            event.setCancelled(true);
            event.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                    "That portal's destination world isn't loaded right now.",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }

        event.setTo(destination);
        event.setSearchRadius(0);
        event.setCreationRadius(0);
        event.setCanCreatePortal(false);
    }
}
