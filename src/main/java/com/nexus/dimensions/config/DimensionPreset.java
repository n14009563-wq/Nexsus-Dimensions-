package com.nexus.dimensions.config;

import org.bukkit.World;

import java.util.List;
import java.util.Map;

/**
 * Fully parsed, validated in-memory representation of one
 * {@code presets/<id>.yml} file. See DESIGN.md section 3 for the schema
 * this is parsed from.
 */
public final class DimensionPreset {

    public String id;
    public String displayName;

    /** Non-null only for Tier 2 presets (custom height / sky requiring a datapack + restart). */
    public WorldHeight worldHeight;

    /**
     * Shorthand for "I just want a different vanilla sky, nothing else
     * custom" — {@code minecraft:the_end}'s void-and-stars sky or {@code
     * minecraft:the_nether}'s ceiling+red-fog sky, decoupled from the
     * height range and biome set you'd otherwise have to also opt into.
     * Only used to build a {@link WorldHeight} when one isn't already
     * given explicitly; see {@link #resolvedWorldHeight()}. Still Tier 2 —
     * changing a dimension's sky effects is a registry-level thing no
     * matter how small the ask sounds. If you don't mind the *other*
     * things bundled into {@code environment: NETHER}/{@code THE_END}
     * (ultrawarm water evaporation, no weather, etc.), that combination
     * is available at Tier 1 with zero restart — see DESIGN.md section 1.
     */
    public Sky sky;

    public World.Environment environment = World.Environment.NORMAL;
    public Long seed;

    public Terrain terrain = new Terrain();
    public Palette palette = new Palette();
    public Biomes biomes = new Biomes();
    public List<CustomBiome> customBiomes = List.of();
    public Trees trees = new Trees();
    public Decorations decorations = new Decorations();
    public Flavor flavor = new Flavor();
    public Particles particles = new Particles();
    public Structures structures = new Structures();
    public Creatures creatures = new Creatures();
    public Seasons seasons = new Seasons();

    /** Tier 2 iff a custom world height, a sky shorthand, or any custom biome was declared. */
    public boolean isTier2() {
        return worldHeight != null || sky != null || !customBiomes.isEmpty();
    }

    /**
     * The {@link WorldHeight} that will actually be written to the
     * datapack: the explicit block if given, otherwise one synthesized
     * from {@link #sky} using accurate vanilla per-dimension defaults, or
     * null for a Tier 1 preset.
     */
    public WorldHeight resolvedWorldHeight() {
        if (worldHeight != null) {
            return worldHeight;
        }
        if (sky == null) {
            return null;
        }
        WorldHeight wh = new WorldHeight();
        // Standard overworld-sized height range; sky's the only thing this shorthand changes.
        wh.minY = -64;
        wh.height = 384;
        switch (sky.effects) {
            case "minecraft:the_nether" -> {
                wh.hasSkylight = false;
                wh.hasCeiling = true;
                wh.ambientLight = 0.1;
            }
            case "minecraft:the_end" -> {
                wh.hasSkylight = false;
                wh.hasCeiling = false;
                wh.ambientLight = 0.0;
            }
            default -> { // minecraft:overworld or anything unrecognized
                wh.hasSkylight = true;
                wh.hasCeiling = false;
                wh.ambientLight = 0.0;
            }
        }
        wh.effects = sky.effects;
        wh.fixedTime = sky.fixedTime;
        return wh;
    }

    public static final class Sky {
        /** minecraft:overworld | minecraft:the_nether | minecraft:the_end */
        public String effects = "minecraft:the_end";
        public Long fixedTime;
    }

    public static final class WorldHeight {
        public int minY = -64;
        public int height = 384;
        public boolean hasCeiling = false;
        public boolean hasSkylight = true;
        public double ambientLight = 0.0;
        public String effects = "minecraft:overworld";
        public Long fixedTime; // null = normal day/night cycle

        // Gameplay flags, deliberately NOT auto-derived from `effects`: picking
        // minecraft:the_nether as a sky look shouldn't silently also make water
        // evaporate or beds stop working unless you ask for that too. These are
        // safe, Overworld-like defaults regardless of which sky you picked;
        // override any of them explicitly if you want the full themed ruleset.
        public boolean ultrawarm = false;
        public boolean natural = true;
        public boolean piglinSafe = false;
        public boolean bedWorks = true;
        public boolean respawnAnchorWorks = false;
        public boolean hasRaids = true;
    }

    public static final class Terrain {
        /**
         * heightmap (default): one height per (x, z) column - fast, simple,
         * matches how vanilla terrain reads, but structurally cannot produce
         * overhangs, arches, or floating islands (there's exactly one
         * surface Y per column, always).
         * <p>
         * density3d: solid/air is evaluated per (x, y, z) block from a 3D
         * density field instead - genuinely 3D terrain. See {@link Density3D}
         * and DESIGN.md section 4b. Tier 1 (our {@code ChunkGenerator})
         * only; Tier 2's datapack-backed worlds always use vanilla flat
         * generation regardless of this setting.
         */
        public String mode = "heightmap";
        public int seaLevel = 63;
        public int baseHeight = 68;
        public int heightVariation = 40;
        public Noise noise = new Noise();
        public Craters craters = new Craters();
        public Caves caves = new Caves();
        public Density3D density3d = new Density3D();

        public boolean isDensity3D() {
            return "density3d".equalsIgnoreCase(mode);
        }
    }

    /**
     * Config for {@code terrain.mode: density3d}. One or more altitude
     * "bands" (floating island layers, or just one band = normal ground)
     * pull the density field positive near their center and negative away
     * from it; 3D fractal noise on top of that is what actually carves the
     * overhangs, arches, and floating chunks instead of a flat disc per
     * band. See {@link Density3DSampler}.
     */
    public static final class Density3D {
        public double threshold = 0.0;
        /** How sharply density falls off away from a band's center - higher = thinner, more clearly separated islands. */
        public double verticalFalloff = 0.02;
        /** Empty = one implicit band built from terrain.baseHeight/heightVariation. */
        public List<Band> bands = List.of();
        /**
         * "bands" (default): the plain band-penalty shape above - floating
         * islands / overhangs / arches. "spires": adds a per-(x,z) density
         * bonus that's strongest near a set of 2D-cellular-noise "spire
         * centers" and fades with horizontal distance from them, so solid
         * mass persists as thin vertical columns reaching up through (and
         * beyond) the band while the space between spire centers mostly
         * falls below threshold — a forest of alien rock needles instead
         * of horizontal island layers. Still uses the same {@code bands}/
         * {@code threshold} underneath; spires are an additive term on top
         * of that, not a replacement mode. Numerically tuned defaults (see
         * DESIGN.md section 4c): at {@code threshold: 0.0} with the
         * defaults below, spire cores sample ~67% solid across a wide
         * height range while the gaps between them sample ~3% solid.
         */
        public String shape = "bands";
        public double spireFrequency = 0.02;
        public double spireJitter = 0.9;
        /** Fraction of a spire-noise cell width where the density bonus is strongest, tapering to 0 by roughly half a cell. Smaller = thinner spires. */
        public double spireCoreFraction = 0.18;
        /** How much extra density bonus a spire's core gets, on top of the normal band penalty. */
        public double spireStrength = 2.5;
        /**
         * Fills AIR-classified blocks at or below {@code palette.liquidLevel}
         * (or {@code terrain.seaLevel} if that's unset) with {@code
         * palette.liquidBlock} — off by default, since density3d historically
         * had no liquid concept at all (see DESIGN.md section 4b). This is a
         * flat per-block Y threshold, NOT a connected-component flood fill:
         * every AIR pocket below the threshold fills, including small gaps
         * between spires/islands, not just a single contiguous ocean
         * surface. Works well for "an ocean far below a layer of floating
         * islands" (there's nothing but open air down there anyway); less
         * well for "a single lake on top of one island" (a stray low pocket
         * elsewhere in the world at the same Y would also fill). See
         * DESIGN.md section 4c for the honest tradeoff this makes instead
         * of the genuinely-flood-filled version.
         */
        public boolean liquids = false;
    }

    public static final class Band {
        public int center = 100;
        public int thickness = 20;
    }

    public static final class Noise {
        public double frequency = 0.01;
        public int octaves = 4;
        public double lacunarity = 2.0;
        public double gain = 0.5;
        public boolean ridged = false;
        public double warp = 0.0;
    }

    public static final class Craters {
        public boolean enabled = false;
        public double frequency = 0.015;
        public int depth = 18;
        public int rimHeight = 5;
        public double jitter = 0.8;
    }

    public static final class Caves {
        public boolean enabled = false;
        public double frequency = 0.02;
        public double threshold = 0.6;
        /**
         * "noise" (default): the original threshold-on-3D-fBm carving,
         * smooth and fairly uniform. "cellular": Worley/cellular-noise
         * carving instead — reads as a network of roughly egg-shaped
         * chambers connected where two cells' territories meet, a
         * distinctly different (and, for a "wild" dimension, often more
         * organic-looking) cave style. See {@link #cellularThreshold}/
         * {@link #cellularJitter} and DESIGN.md section 4c. Heightmap
         * terrain only, same as the rest of {@code caves} — density3d's
         * caves are inherent to its own density formula.
         */
        public String mode = "noise";
        /**
         * Only used when mode is "cellular". Numerically verified (see
         * DESIGN.md section 4c): ~0.30-0.35 carves roughly 11-18% of
         * sampled space regardless of frequency, a comparable overall cave
         * density to a typical "noise" mode preset. Higher = more carved.
         */
        public double cellularThreshold = 0.32;
        /** Only used when mode is "cellular". 0 = chambers on a perfectly regular grid; 1 = fully jittered/organic. */
        public double cellularJitter = 0.9;
    }

    public static final class Palette {
        public String surfaceBlock = "minecraft:grass_block";
        public String subsurfaceBlock = "minecraft:dirt";
        public int subsurfaceDepth = 4;
        public String deepBlock = "minecraft:stone";
        public String liquidBlock = "minecraft:water";
        /** -1 = "use terrain.seaLevel". */
        public int liquidLevel = -1;
        /**
         * Optional patches of an alternate surface/subsurface block,
         * bleeding through the primary palette in organic blobs instead of
         * every block of a given layer being identical — mineral veins,
         * color variation, crystal patches, whatever a "wild" terrain
         * wants. Checked in list order; the first variant whose noise
         * field is above its threshold at a given column wins, otherwise
         * the primary palette block is used. See DESIGN.md section 4c.
         */
        public List<PaletteVariant> variants = List.of();
        /**
         * Optional light-emitting or eye-catching blocks scattered as
         * small clustered pockets through deep terrain (not surface/
         * subsurface) — an ore-vein-style mechanic for atmosphere rather
         * than resources. See DESIGN.md section 4c.
         */
        public List<GlowDeposit> glowDeposits = List.of();
    }

    public static final class PaletteVariant {
        public String name = "variant";
        /** null = don't override this layer's block for this variant (keep the primary palette's). */
        public String surfaceBlock;
        public String subsurfaceBlock;
        public double frequency = 0.015;
        /**
         * Raw 2D fBm threshold this variant's own noise field must exceed
         * to win a column — NOT a direct "percent coverage," since fBm's
         * output clusters near 0 (roughly bell-curve-shaped, being a sum
         * of octaves) rather than being uniform. Numerically sampled
         * reference (3-octave fBm, see DESIGN.md section 4c): threshold
         * 0.0 ~ 50% of columns, 0.1 ~ 28%, 0.15 ~ 19%, 0.2 ~ 12%, 0.3 ~ 4%.
         * Start around 0.15-0.2 for "occasional patches."
         */
        public double threshold = 0.15;
    }

    public static final class GlowDeposit {
        public String block = "minecraft:glowstone";
        public double frequency = 0.05;
        /**
         * Raw 2-octave 3D fBm threshold, same "not a literal percentage"
         * caveat as {@link PaletteVariant#threshold}. Numerically sampled
         * reference (see DESIGN.md section 4c): threshold 0.3 ~ 7% of deep
         * blocks, 0.4 ~ 2%, 0.5 ~ 0.5%. Start around 0.4-0.45 for "rare
         * clustered pockets you stumble into while caving."
         */
        public double threshold = 0.42;
    }

    public static final class Biomes {
        public String mode = "single"; // single | blended
        public List<BiomeEntry> entries = List.of(new BiomeEntry());
    }

    public static final class BiomeEntry {
        public String id = "minecraft:plains";
        public double weight = 1.0;
    }

    public static final class CustomBiome {
        public String id; // e.g. nexus:violet_canopy
        public String category = "none";
        public double temperature = 0.8;
        public double downfall = 0.4;
        public String skyColor = "0x78A7FF";
        public String fogColor = "0xC0D8FF";
        public String waterColor = "0x3F76E4";
        public String waterFogColor = "0x050533";
    }

    public static final class Trees {
        public boolean enabled = false;
        /** Used directly when {@link #species} is empty (single-species preset, the original schema). */
        public int minHeight = 6;
        public int maxHeight = 12;
        public int canopyRadius = 4;
        public String trunkBlock = "minecraft:oak_log";
        public String leafBlock = "minecraft:oak_leaves";
        public double rarityPerChunk = 0.1;
        public int giantCanopyLayers = 6;
        /** See the matching field on {@link TreeSpecies} — used directly here when {@link #species} is empty. */
        public boolean branches = true;
        public boolean buttressRoots = true;
        public String canopyAccentBlock;
        public double canopyAccentChance = 0.03;
        public String trunkAccentBlock;
        public double trunkAccentChance = 0.02;
        public String vineBlock;
        public double vineChance = 0.15;
        public int vineMinLength = 2;
        public int vineMaxLength = 6;
        /**
         * Optional weighted multi-species palette — when non-empty, each
         * tree anchor chunk picks one species (weighted random, same
         * deterministic-hash approach as everything else) instead of every
         * tree in the dimension being identical. Empty (default) means
         * "one species," built from this class's own fields — see
         * {@code TreeSpeciesPicker} for the fallback. See DESIGN.md
         * section 5.
         */
        public List<TreeSpecies> species = List.of();
    }

    public static final class TreeSpecies {
        public String name = "default";
        /** Relative weight, not required to sum to 1.0 — normalized against the other species' weights. */
        public double weight = 1.0;
        public int minHeight = 6;
        public int maxHeight = 12;
        public int canopyRadius = 4;
        public String trunkBlock = "minecraft:oak_log";
        public String leafBlock = "minecraft:oak_leaves";
        public int giantCanopyLayers = 6;
        /**
         * Secondary limbs splitting off partway up the trunk on tall
         * enough trees (each with its own small canopy blob at the tip) —
         * on by default, a purely aesthetic upgrade with no config needed
         * to see it. See {@code TreeShaper} and DESIGN.md section 5.
         */
        public boolean branches = true;
        /** Flared root blocks radiating from the trunk base — on by default, same reasoning as {@link #branches}. */
        public boolean buttressRoots = true;
        /**
         * Optional decoration replacing occasional canopy leaf blocks —
         * bioluminescent accents (minecraft:shroomlight, sea_lantern),
         * "fruit" (any block key works, this isn't limited to real fruit
         * blocks), crystal growths (minecraft:amethyst_cluster), whatever
         * fits the species. Null (default) = no accents.
         */
        public String canopyAccentBlock;
        public double canopyAccentChance = 0.03;
        /** Same idea as {@link #canopyAccentBlock}, replacing occasional trunk blocks instead. */
        public String trunkAccentBlock;
        public double trunkAccentChance = 0.02;
        /**
         * Hanging vine strands grown downward from qualifying canopy-
         * underside leaf blocks. Null (default) = no vines.
         * minecraft:weeping_vines reads well for this (grows/hangs
         * downward already in vanilla); minecraft:twisting_vines or
         * minecraft:vine also work.
         */
        public String vineBlock;
        public double vineChance = 0.15;
        public int vineMinLength = 2;
        public int vineMaxLength = 6;
    }

    /**
     * Small ground-clutter decorations — boulders, crystal shards, fungal
     * caps, floating debris — placed much more densely than trees or
     * structures (this is meant to be common texture, not a rare
     * landmark), on its own deterministic-hash salt range (900+) so it
     * never correlates with trees (1-5), structures (20-22), or tree
     * accents (700-800+). See {@code DecorationPopulator}/{@code
     * NexusDecorationListener} and DESIGN.md section 5c.
     */
    public static final class Decorations {
        public boolean enabled = false;
        /** Independent placement candidates rolled per chunk — each one may or may not actually place anything (see chancePerAttempt). Keep this small; it's a per-chunk cost multiplier. */
        public int perChunkAttempts = 3;
        /** Probability a given attempt actually places a decoration once it's rolled a candidate position. */
        public double chancePerAttempt = 0.25;
        /** Weighted palette of what actually gets placed — required when enabled; PresetLoader disables decorations with a warning if this is empty. */
        public List<DecorationSpecies> species = List.of();
    }

    public static final class DecorationSpecies {
        public String name = "default";
        /** Relative weight, not required to sum to 1.0 — normalized against the other species' weights. */
        public double weight = 1.0;
        /** Primary column block — a single boulder when minHeight==maxHeight==1 and no cap, or a stem/trunk under a capped formation. */
        public String block = "minecraft:stone";
        public int minHeight = 1;
        public int maxHeight = 1;
        /** null (default) = no cap layer, just the column itself. */
        public String capBlock;
        /** 0 (default) = the cap is a single block directly on top of the column. Above 0 = a flat disc of capBlock at that radius (mushroom-cap / boulder-crown look) — small values only, this is a flat disc, not a sphere. */
        public int capRadius = 0;
        /** Both 0 (default) = rooted directly on the ground (groundY + 1). Above 0 lets the whole formation hover — alien floating debris/crystal shards, especially over density3d terrain. */
        public int minFloatHeight = 0;
        public int maxFloatHeight = 0;
    }

    public static final class Flavor {
        /**
         * 1.0 = normal vanilla feel. Below 1.0 = floatier (higher jumps,
         * slow-falling, no fall damage) - moons live around 0.2-0.4.
         * Above 1.0 = heavier (reduced jump height, a little slowness) -
         * around 1.5-2.5 for "hard to move." See GravityService for the
         * exact vanilla-effect mapping and DESIGN.md section 7 for why
         * it's built entirely on real potion effects instead of hand-rolled
         * velocity hacks.
         */
        public double gravity = 1.0;
        /**
         * null = auto (false once gravity is severe enough to count as
         * "grounded," true otherwise). Set explicitly to force a world
         * where players flatly cannot jump, or to guarantee jumping stays
         * allowed even at extreme gravity values.
         */
        public Boolean allowJumping;
        public boolean alwaysClearWeather = false;
        public boolean generateStructures = false;
        public boolean generateDecorations = false;
        public boolean generateVanillaCaves = false;
    }

    /**
     * Config-driven ambient particle "weather" (dust storms, ash fall,
     * pollen, snow flurries, whatever) - built entirely on vanilla's own
     * {@code Particle} API, no resource pack. See DESIGN.md section 8.
     */
    public static final class Particles {
        public boolean enabled = false;
        /** A org.bukkit.Particle enum name, e.g. ASH, CLOUD, SNOWFLAKE, CRIT, DUST. */
        public String type = "ASH";
        /** Only used when type is DUST or DUST_COLOR_TRANSITION. */
        public String color = "0xC9A8FF";
        public String toColor; // only used for DUST_COLOR_TRANSITION
        public float size = 1.0f;
        /** Particles spawned per player per batch. */
        public int density = 25;
        /** Horizontal radius around each player particles are scattered within. */
        public int radius = 14;
        /** Vertical spread around the player's eye height. */
        public int heightSpread = 6;
        /** Ticks between batches. */
        public int intervalTicks = 4;
        /**
         * 0 = purely visual. Above 0, also nudges players' horizontal
         * velocity gently in a slowly-rotating direction - capped low on
         * purpose (see GravityService/AmbientParticleService) so it reads
         * as "wind" instead of fighting player movement.
         */
        public double windStrength = 0.0;
    }

    /**
     * Config-driven structure placement (ruins, monuments, whatever a
     * blueprint describes) — placed the same anchor-chunk way giant trees
     * are (see StructurePopulator), Tier 1 only. See DESIGN.md section 9.
     */
    public static final class Structures {
        public boolean enabled = false;
        /** Name of a {@code blueprints/<name>.yml} file (see Blueprint/BlueprintLoader). */
        public String blueprint;
        public double rarityPerChunk = 0.005;
        /**
         * A org.bukkit.loot.LootTables enum name (e.g. SIMPLE_DUNGEON,
         * ABANDONED_MINESHAFT) applied to any chest the blueprint places.
         * Null/blank = chests are left empty.
         */
        public String lootTable;
        /**
         * Randomly rotates each placed blueprint instance 0/90/180/270
         * degrees around its vertical axis, deterministically per anchor
         * chunk (a regenerated chunk still gets the same rotation). This
         * transforms block *positions* only — it does not re-orient
         * directional block states (stairs facing, log axis, chest facing,
         * ...), so it's safe for blueprints built from non-directional
         * blocks (like the bundled {@code ruin_small}) but will leave
         * directional blocks facing their original way after being moved to
         * a rotated position. See DESIGN.md section 9 and {@code
         * com.nexus.dimensions.structure.BlueprintTransform}.
         */
        public boolean randomRotation = false;
        /** Combines with {@link #randomRotation} for 8 possible orientations instead of 4 — same position-only caveat. */
        public boolean randomMirror = false;
    }

    /**
     * Config-driven mob customization — no new mob species (that needs an
     * NMS-level custom entity, off the table for a vanilla-only plugin, see
     * DESIGN.md section 1), just real vanilla mobs with edited attributes,
     * equipment, a custom name, and a glow, so a dimension's hostile
     * population reads as native to it instead of an unmodified Overworld
     * mob that wandered in. See MobCustomizationListener and DESIGN.md
     * section 11.
     * <p>
     * This does not create spawn opportunities that don't already exist —
     * whether a given entity type is eligible to naturally spawn at all is
     * still entirely vanilla's own biome/mob-category logic (unaffected by
     * this plugin); {@code mobs} only customizes types that do spawn.
     */
    public static final class Creatures {
        public boolean enabled = false;
        /**
         * 1.0 = vanilla spawn rate. Below 1.0 thins natural spawns by
         * randomly cancelling that fraction of them. Values above 1.0 are
         * NOT supported this way (there's no legitimate Bukkit hook to make
         * vanilla attempt spawns *more* often, only to react to/cancel ones
         * it already decided to try) — see MobCustomizationListener, which
         * clamps to 1.0 and warns instead of silently doing nothing.
         */
        public double spawnMultiplier = 1.0;
        /** Keyed by org.bukkit.entity.EntityType name, e.g. "ZOMBIE", "ENDERMAN". */
        public Map<String, MobProfile> mobs = Map.of();
    }

    public static final class MobProfile {
        public String displayName;
        public boolean alwaysShowName = false;
        public double healthMultiplier = 1.0;
        public double speedMultiplier = 1.0;
        /** Only affects mobs with a GENERIC_ATTACK_DAMAGE attribute (most melee hostiles); silently ignored otherwise. */
        public double damageMultiplier = 1.0;
        /**
         * Visual size multiplier via Attribute.GENERIC_SCALE (added in
         * 1.20.5) — see README's "if anything fails to compile" list, this
         * is the newest/least certain attribute referenced in this project.
         */
        public double scale = 1.0;
        public boolean glowing = false;
        /** Equipment slot ("hand"|"offhand"|"head"|"chest"|"legs"|"feet") -> minecraft:<item> key. All drop chances forced to 0. */
        public Map<String, String> equipment = Map.of();
    }

    /**
     * Cosmetic v1 seasons: a preset-defined cycle of named stages, each
     * lasting {@code durationTicks} real server ticks, that can override
     * the ambient particles, thin/restore natural mob spawning, and
     * force-clear or allow weather while active. Deliberately does not
     * touch already-generated terrain (a "winter" that reskins existing
     * blocks would need to rewrite every already-explored chunk — a much
     * bigger feature, see DESIGN.md section 8's closing note and section
     * 12's roadmap entry for why that's out of scope here). Progress
     * through the cycle is tracked in memory only and resets on restart —
     * not persisted, since a few seconds of a stage restarting is harmless
     * and not worth a new data file for.
     */
    public static final class Seasons {
        public boolean enabled = false;
        public List<SeasonStage> stages = List.of();
    }

    public static final class SeasonStage {
        public String name = "default";
        /** 24000 = one vanilla day/night cycle's worth of real ticks. */
        public int durationTicks = 24000;
        /** null = this stage doesn't change ambient particles; the preset's base `particles` block still applies. */
        public Particles particles;
        /** null = this stage doesn't change spawn thinning; preset.creatures.spawnMultiplier still applies. */
        public Double spawnMultiplierOverride;
        /** null = defer to preset.flavor.alwaysClearWeather; true/false forces that behavior only while this stage is active. */
        public Boolean forceClearWeather;
    }
}
