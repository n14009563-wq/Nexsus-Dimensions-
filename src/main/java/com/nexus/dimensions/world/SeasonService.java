package com.nexus.dimensions.world;

import com.nexus.dimensions.config.DimensionPreset;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cosmetic v1 seasons: advances each seasons-enabled dimension through its
 * configured {@code SeasonStage} cycle and exposes the "effective" (season-
 * adjusted) values other services should use instead of reading straight
 * off the preset. See DESIGN.md section 8's closing note and
 * {@link DimensionPreset.Seasons}'s javadoc for why this only touches
 * particles/spawn-thinning/weather and not placed terrain.
 * <p>
 * Elapsed time per world is tracked in memory only (a plain tick counter,
 * advanced once per second of real time rather than every tick — a season
 * system has no need for tick-perfect precision) and resets on restart.
 * That's a deliberate simplicity tradeoff: persisting exact season phase
 * would need its own small data file for a cosmetic feature where "the
 * season stage reset to the start of the cycle after a restart" is a total
 * non-issue.
 */
public final class SeasonService {

    private static final long ADVANCE_INTERVAL_TICKS = 20L; // once per real second

    private final DimensionManager dimensionManager;
    /** worldName -> elapsed ticks since this service started tracking it. */
    private final Map<String, Long> elapsedTicks = new ConcurrentHashMap<>();

    public SeasonService(Plugin plugin, DimensionManager dimensionManager) {
        this.dimensionManager = dimensionManager;
        Bukkit.getScheduler().runTaskTimer(plugin, this::advance, ADVANCE_INTERVAL_TICKS, ADVANCE_INTERVAL_TICKS);
    }

    private void advance() {
        for (World world : Bukkit.getWorlds()) {
            DimensionPreset preset = dimensionManager.getPresetForWorld(world.getName());
            if (preset == null || !preset.seasons.enabled) {
                continue;
            }
            elapsedTicks.merge(world.getName(), ADVANCE_INTERVAL_TICKS, Long::sum);
        }
    }

    /** The active stage for this world's preset, or null if seasons are off/unconfigured. */
    public DimensionPreset.SeasonStage currentStage(String worldName, DimensionPreset preset) {
        if (preset == null || !preset.seasons.enabled || preset.seasons.stages.isEmpty()) {
            return null;
        }
        List<DimensionPreset.SeasonStage> stages = preset.seasons.stages;
        long cycleLength = 0;
        for (DimensionPreset.SeasonStage s : stages) {
            cycleLength += s.durationTicks;
        }
        if (cycleLength <= 0) {
            return null;
        }
        long elapsed = elapsedTicks.getOrDefault(worldName, 0L) % cycleLength;
        long cursor = 0;
        for (DimensionPreset.SeasonStage s : stages) {
            cursor += s.durationTicks;
            if (elapsed < cursor) {
                return s;
            }
        }
        return stages.get(stages.size() - 1); // rounding fallback, shouldn't normally hit
    }

    /** The particle config {@link AmbientParticleService} should actually use right now. */
    public DimensionPreset.Particles effectiveParticles(String worldName, DimensionPreset preset) {
        DimensionPreset.SeasonStage stage = currentStage(worldName, preset);
        if (stage != null && stage.particles != null) {
            return stage.particles;
        }
        return preset.particles;
    }

    /** The natural-spawn thinning factor {@link MobCustomizationListener} should actually use right now. */
    public double effectiveSpawnMultiplier(String worldName, DimensionPreset preset) {
        DimensionPreset.SeasonStage stage = currentStage(worldName, preset);
        if (stage != null && stage.spawnMultiplierOverride != null) {
            return stage.spawnMultiplierOverride;
        }
        return preset.creatures.spawnMultiplier;
    }

    /** Whether weather should currently be forced clear, factoring in any active season override. */
    public boolean effectiveAlwaysClearWeather(String worldName, DimensionPreset preset) {
        DimensionPreset.SeasonStage stage = currentStage(worldName, preset);
        if (stage != null && stage.forceClearWeather != null) {
            return stage.forceClearWeather;
        }
        return preset.flavor.alwaysClearWeather;
    }
}
