package com.nexus.dimensions.world;

import com.nexus.dimensions.config.DimensionPreset;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Random;

/**
 * Config-driven ambient "weather" particles — dust storms, ash fall,
 * pollen, snow flurries, whatever a preset describes — built entirely on
 * vanilla's {@link Particle} API. No resource pack, no custom client
 * rendering; every particle type here is one the base game already ships.
 * <p>
 * Optionally also nudges players' horizontal velocity a small, capped
 * amount in a slowly-rotating shared direction when {@code windStrength}
 * is set above 0 — deliberately subtle. This is the same reasoning as
 * {@link GravityService}: a strong hand-rolled velocity push fights the
 * client's movement prediction and reads as jank over anything but a LAN
 * connection, so this stays small enough to feel like weather pushing
 * back, not a wind tunnel throwing the player around.
 */
public final class AmbientParticleService {

    private static final double MAX_WIND_PER_TICK = 0.3;

    private final DimensionManager dimensionManager;
    /** Nullable — seasons are optional; when unset every world just uses its preset's plain `particles` block. */
    private final SeasonService seasonService;
    private final Random random = new Random();
    private long tick = 0;

    public AmbientParticleService(Plugin plugin, DimensionManager dimensionManager) {
        this(plugin, dimensionManager, null);
    }

    public AmbientParticleService(Plugin plugin, DimensionManager dimensionManager, SeasonService seasonService) {
        this.dimensionManager = dimensionManager;
        this.seasonService = seasonService;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 1L);
    }

    private void tick() {
        tick++;
        double windAngle = (tick % 12000) / 12000.0 * (2 * Math.PI); // one full slow rotation every 10 minutes

        for (Player player : Bukkit.getOnlinePlayers()) {
            String worldName = player.getWorld().getName();
            DimensionPreset preset = dimensionManager.getPresetForWorld(worldName);
            if (preset == null) {
                continue;
            }
            DimensionPreset.Particles cfg = seasonService != null
                    ? seasonService.effectiveParticles(worldName, preset)
                    : preset.particles;
            if (!cfg.enabled) {
                continue;
            }
            if (tick % cfg.intervalTicks != 0) {
                continue;
            }

            spawnBatch(player, cfg);

            if (cfg.windStrength > 0) {
                nudge(player, cfg.windStrength, windAngle);
            }
        }
    }

    private void spawnBatch(Player player, DimensionPreset.Particles cfg) {
        World world = player.getWorld();
        Particle particle = resolveParticle(cfg.type);
        Object data = "DUST".equals(cfg.type) ? dustOptions(cfg) : null;

        Location eye = player.getEyeLocation();
        for (int i = 0; i < cfg.density; i++) {
            double angle = random.nextDouble() * 2 * Math.PI;
            double dist = random.nextDouble() * cfg.radius;
            double dx = Math.cos(angle) * dist;
            double dz = Math.sin(angle) * dist;
            double dy = (random.nextDouble() - 0.5) * cfg.heightSpread;
            Location loc = eye.clone().add(dx, dy, dz);
            if (data != null) {
                world.spawnParticle(particle, loc, 1, 0, 0, 0, 0, data);
            } else {
                world.spawnParticle(particle, loc, 1, 0, 0, 0, 0);
            }
        }
    }

    private void nudge(Player player, double strength, double sharedAngle) {
        double magnitude = Math.min(strength, MAX_WIND_PER_TICK);
        Vector wind = new Vector(Math.cos(sharedAngle) * magnitude, 0, Math.sin(sharedAngle) * magnitude);
        Vector result = player.getVelocity().add(wind);
        if (result.length() > MAX_WIND_PER_TICK * 4) {
            result = result.normalize().multiply(MAX_WIND_PER_TICK * 4);
        }
        player.setVelocity(result);
    }

    private static Particle resolveParticle(String typeName) {
        try {
            return Particle.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            return Particle.ASH;
        }
    }

    private static Particle.DustOptions dustOptions(DimensionPreset.Particles cfg) {
        return new Particle.DustOptions(parseColor(cfg.color), cfg.size);
    }

    private static Color parseColor(String hex) {
        if (hex == null) {
            return Color.WHITE;
        }
        String cleaned = hex.trim().toLowerCase(Locale.ROOT);
        try {
            if (cleaned.startsWith("0x")) {
                cleaned = cleaned.substring(2);
            } else if (cleaned.startsWith("#")) {
                cleaned = cleaned.substring(1);
            }
            int rgb = Integer.parseInt(cleaned, 16);
            return Color.fromRGB(rgb);
        } catch (NumberFormatException e) {
            return Color.WHITE;
        }
    }
}
