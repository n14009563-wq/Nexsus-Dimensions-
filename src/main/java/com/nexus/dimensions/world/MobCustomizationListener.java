package com.nexus.dimensions.world;

import com.nexus.dimensions.config.DimensionPreset;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Reskins real vanilla mobs per-dimension instead of adding new species —
 * a genuinely new mob needs an NMS-level custom entity class, off the table
 * for a plugin that only touches public Bukkit/Paper API (see DESIGN.md
 * section 1's honest capability picture). What's on the table, and what
 * this does: attribute overrides (health/speed/damage/size), equipment,
 * a custom name, a glow, and thinning (never boosting) natural spawn
 * frequency. See DESIGN.md section 11.
 * <p>
 * {@code creatures.spawnMultiplier} is read and applied regardless of
 * {@code creatures.enabled} — a preset that just wants fewer (or, at
 * {@code 0.0}, effectively zero) natural mobs doesn't need to also turn on
 * reskinning to get there. {@code creatures.enabled} only gates the
 * reskin/{@code applyProfile} half of this class.
 * <p>
 * This intentionally does not decide *whether* a mob spawns in the first
 * place (beyond the thinning above) — that's still entirely vanilla's own
 * biome/mob-category spawn logic, unaffected by this plugin. A preset that
 * lists a {@code creatures.mobs} entry for a type the biome never naturally
 * produces simply never sees that customization fire; it isn't a bug, it's
 * the plugin not overriding vanilla's spawn-eligibility rules.
 */
public final class MobCustomizationListener implements Listener {

    private final DimensionManager dimensionManager;
    /** Nullable — seasons are optional; when unset every world just uses its preset's plain spawnMultiplier. */
    private final SeasonService seasonService;

    public MobCustomizationListener(Plugin plugin, DimensionManager dimensionManager) {
        this(plugin, dimensionManager, null);
    }

    public MobCustomizationListener(Plugin plugin, DimensionManager dimensionManager, SeasonService seasonService) {
        this.dimensionManager = dimensionManager;
        this.seasonService = seasonService;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        String worldName = event.getEntity().getWorld().getName();
        DimensionPreset preset = dimensionManager.getPresetForWorld(worldName);
        if (preset == null) {
            return;
        }

        // Spawn thinning is intentionally independent of creatures.enabled: that flag only
        // gates the *reskinning* feature below (attributes/equipment/name/glow). A preset
        // that just wants fewer (or zero) natural mobs shouldn't have to also opt into
        // reskinning and populate a dummy creatures.mobs entry to get there - see README
        // "Thinning natural spawns" / DESIGN.md section 11.
        double spawnMultiplier = seasonService != null
                ? seasonService.effectiveSpawnMultiplier(worldName, preset)
                : preset.creatures.spawnMultiplier;
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL
                && spawnMultiplier < 1.0
                && ThreadLocalRandom.current().nextDouble() >= spawnMultiplier) {
            event.setCancelled(true);
            return;
        }

        if (!preset.creatures.enabled) {
            return; // reskinning off - thinning above (if any) already happened
        }
        DimensionPreset.MobProfile profile = preset.creatures.mobs.get(event.getEntityType().name());
        if (profile == null) {
            return;
        }
        applyProfile(event.getEntity(), profile);
    }

    private void applyProfile(Entity entity, DimensionPreset.MobProfile profile) {
        if (!(entity instanceof LivingEntity living)) {
            return;
        }

        applyAttribute(living, Attribute.GENERIC_MAX_HEALTH, profile.healthMultiplier);
        // Freshly spawned, so the entity should already be at its (old) max health;
        // re-fill to the new max rather than leaving it at a now-stale value.
        AttributeInstance maxHealth = living.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (maxHealth != null) {
            living.setHealth(maxHealth.getValue());
        }
        applyAttribute(living, Attribute.GENERIC_MOVEMENT_SPEED, profile.speedMultiplier);
        applyAttribute(living, Attribute.GENERIC_ATTACK_DAMAGE, profile.damageMultiplier);
        if (profile.scale != 1.0) {
            // Attribute.GENERIC_SCALE - added in 1.20.5. See README's uncertain-API list;
            // if this doesn't resolve, delete this one call and everything else still works.
            applyAttribute(living, Attribute.GENERIC_SCALE, profile.scale);
        }

        if (profile.displayName != null && !profile.displayName.isBlank()) {
            living.setCustomName(profile.displayName);
            living.setCustomNameVisible(profile.alwaysShowName);
        }
        living.setGlowing(profile.glowing);

        if (!profile.equipment.isEmpty()) {
            EntityEquipment equipment = living.getEquipment();
            if (equipment != null) {
                for (Map.Entry<String, String> entry : profile.equipment.entrySet()) {
                    ItemStack item = new ItemStack(materialOf(entry.getValue()));
                    switch (entry.getKey()) {
                        case "hand" -> {
                            equipment.setItemInMainHand(item);
                            equipment.setItemInMainHandDropChance(0f);
                        }
                        case "offhand" -> {
                            equipment.setItemInOffHand(item);
                            equipment.setItemInOffHandDropChance(0f);
                        }
                        case "head" -> {
                            equipment.setHelmet(item);
                            equipment.setHelmetDropChance(0f);
                        }
                        case "chest" -> {
                            equipment.setChestplate(item);
                            equipment.setChestplateDropChance(0f);
                        }
                        case "legs" -> {
                            equipment.setLeggings(item);
                            equipment.setLeggingsDropChance(0f);
                        }
                        case "feet" -> {
                            equipment.setBoots(item);
                            equipment.setBootsDropChance(0f);
                        }
                        default -> { /* unknown slot key - PresetLoader doesn't validate this list, ignore */ }
                    }
                }
            }
        }
    }

    /** multiplier == 1.0 is a deliberate no-op so unconfigured attributes are left exactly as vanilla spawned them. */
    private void applyAttribute(LivingEntity living, Attribute attribute, double multiplier) {
        if (multiplier == 1.0) {
            return;
        }
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance == null) {
            return; // this entity type doesn't have this attribute (e.g. attack damage on a passive mob) - fine, skip
        }
        instance.setBaseValue(instance.getBaseValue() * multiplier);
    }

    private static Material materialOf(String key) {
        Material m = Material.matchMaterial(key);
        return m != null ? m : Material.AIR;
    }
}
