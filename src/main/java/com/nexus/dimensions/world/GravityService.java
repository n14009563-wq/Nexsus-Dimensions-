package com.nexus.dimensions.world;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import com.nexus.dimensions.config.DimensionPreset;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Per-world "gravity" built entirely on real vanilla mechanics — {@code
 * JUMP_BOOST} (scaled, including negative amplifiers for heavy worlds),
 * {@code SLOW_FALLING} (which also natively zeroes fall damage, so there's
 * no hand-rolled damage-scaling code anymore), and outright cancelling
 * {@link PlayerJumpEvent} for worlds severe enough to be "grounded."
 * <p>
 * This deliberately does <b>not</b> hand-modify player velocity every tick
 * the way a first-pass implementation might. Vanilla potion effects are
 * simulated by the client itself, so they stay smooth under normal
 * network conditions; continuously overwriting {@code Player#setVelocity}
 * fights the client's own movement prediction and reads as rubber-banding
 * over anything but a LAN connection. See DESIGN.md section 7.
 * <p>
 * Import note: {@link PlayerJumpEvent} lives under the older
 * {@code com.destroystokyo.paper.event.player} package rather than the
 * newer {@code io.papermc.paper.event.player} one — Paper never moved its
 * pre-fork custom events, only new ones land in the newer package. If this
 * import doesn't resolve on the exact Paper build you're compiling
 * against, that's the first thing to check; the newer package is the
 * fallback to try.
 */
public final class GravityService implements Listener {

    /** Backstop refresh so effects survive milk buckets, death, or another plugin clearing them. */
    private static final long REFRESH_INTERVAL_TICKS = 100L;
    /** Effectively permanent — see REFRESH_INTERVAL_TICKS for why a literal infinite isn't needed. */
    private static final int EFFECT_DURATION_TICKS = Integer.MAX_VALUE;
    /** gravity values at/above this default to "can't jump" unless a preset says otherwise. */
    private static final double GROUNDED_THRESHOLD = 2.5;

    private final Plugin plugin;
    private final DimensionManager dimensionManager;

    public GravityService(Plugin plugin, DimensionManager dimensionManager) {
        this.plugin = plugin;
        this.dimensionManager = dimensionManager;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshAll, 20L, REFRESH_INTERVAL_TICKS);
    }

    private void refreshAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            apply(player);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        apply(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // Respawn can move the player to a different world (e.g. a bed/anchor
        // elsewhere); apply next tick once the respawn location is final.
        Bukkit.getScheduler().runTask(plugin, () -> apply(event.getPlayer()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onJump(PlayerJumpEvent event) {
        DimensionPreset preset = dimensionManager.getPresetForWorld(event.getPlayer().getWorld().getName());
        if (preset != null && !allowsJumping(preset)) {
            event.setCancelled(true);
        }
    }

    private void apply(Player player) {
        DimensionPreset preset = dimensionManager.getPresetForWorld(player.getWorld().getName());
        double gravity = preset != null ? preset.flavor.gravity : 1.0;

        // Only touch effects we own; clearing unconditionally on every apply keeps
        // stale amplifiers from a previous world/preset from lingering.
        player.removePotionEffect(PotionEffectType.SLOW_FALLING);
        player.removePotionEffect(PotionEffectType.JUMP_BOOST);

        if (preset == null || gravity == 1.0) {
            return; // vanilla feel, nothing to apply
        }

        int jumpAmplifier = jumpAmplifier(gravity);
        if (jumpAmplifier != 0 && (preset.flavor.allowJumping == null || preset.flavor.allowJumping)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, EFFECT_DURATION_TICKS,
                    jumpAmplifier, true, false, false));
        }

        if (gravity < 1.0) {
            // SLOW_FALLING also natively zeroes fall damage while active - the
            // gravity system relies on that instead of a separate damage listener.
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, EFFECT_DURATION_TICKS,
                    0, true, false, false));
        }
    }

    /** Whether the preset's resolved gravity still lets players leave the ground at all. */
    public static boolean allowsJumping(DimensionPreset preset) {
        if (preset.flavor.allowJumping != null) {
            return preset.flavor.allowJumping;
        }
        return preset.flavor.gravity < GROUNDED_THRESHOLD;
    }

    /**
     * gravity 1.0 -> 0 (no change). Below 1.0 -> positive amplifier (higher
     * jump); above 1.0 -> negative amplifier (lower jump). Deliberately
     * gentle in the heavy direction — the certain mechanism for "can't
     * jump at all" is {@link #allowsJumping}/{@link #onJump}, not stacking
     * amplifiers past whatever point the client stops rendering sanely.
     */
    static int jumpAmplifier(double gravity) {
        double raw = (1.0 / gravity) - 1.0;
        int amplifier = (int) Math.round(raw * 2);
        return Math.max(-8, Math.min(8, amplifier));
    }
}
