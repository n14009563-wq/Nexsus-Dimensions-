# Nexus Dimensions — Design Doc

Config-driven custom dimensions for Paper 1.21.x, in the spirit of Nexus
Terra: every "dimension" is a fully generated world with its own terrain,
biomes, flora, and (optionally) its own physical rules — moons, ice moons,
ocean planets, or anything else you can describe in a preset file.

## 0. This is infinite, not a decorated spawn bubble

Worth stating plainly since it's the difference between a toy and
something worth building on: **there is no spawn-radius concept anywhere
in this codebase.** A Bukkit `World` doesn't work like "the loaded/rendered
area near spawn is special" — every single chunk the server ever needs,
at any coordinate, in any direction, at any point in the future, goes
through the exact same call: `NexusChunkGenerator.generateNoise(...)` for
that chunk's own (x, z). There's no boundary where it hands off to
vanilla, because vanilla's own generator is never invoked at all for a
Tier 1 world — `shouldGenerateSurface()`/`shouldGenerateCaves()` are
hard-overridden to `false` specifically so nothing vanilla can leak in.
The noise functions themselves (`NoiseUtil.fbm2D`, `worley2D`) are pure
math with no falloff term — sampled at spawn or 30 million blocks out,
they behave identically (verified numerically while building this; see
the README build notes). `applyFlavor()` also explicitly sets the world
border to vanilla's own effective maximum (~60,000,000 blocks) rather
than leaving it to a server default, so there's no artificial ceiling on
how far this extends either.

The one real way to accidentally get "custom near spawn, vanilla
further out" isn't a bug in the generator — it's reusing a world folder
that already had chunks saved to disk before Nexus Dimensions touched it
(an old vanilla world, a copy, leftovers from another plugin). Minecraft
never regenerates a chunk that's already saved; it only generates chunks
it hasn't seen before. So an already-explored region keeps whatever
terrain it had, while newly explored terrain around it uses the preset —
which looks exactly like "spawn is customized, then it reverts."
`DimensionManager.createOrLoad` now detects this specifically (checks for
existing `.mca` region files before creating) and the `/nexusdim create`
command will warn you loudly in red text if it happens, telling you to
delete the folder and recreate it if you want something genuinely fresh.
**Practical rule: always create dimensions under brand-new world names
you've never used before**, and this failure mode never comes up.

## 1. The honest technical picture first

Paper is a **server** platform sitting on top of vanilla Minecraft's data
model, not a client mod loader. That gives Nexus Dimensions two very
different levels of power, and the design leans into both instead of
pretending they're the same thing:

**Tier 1 — Instant worlds (no restart).** A plugin can create a brand new
`World` at any moment with `WorldCreator`, wire in a fully custom
`ChunkGenerator` and `BiomeProvider`, and get real, unique terrain,
palettes, and biome placement out of it immediately, from an in-game
command. What it *cannot* do at this tier: change a world's build-height
range or invent a dimension type with new sky/ceiling/gravity effects,
because those are baked into Minecraft's `DimensionType` registry, which
is only extended by data (JSON), and new registry entries are only picked
up when the server (re)starts.

**Tier 2 — Datapack dimensions (one restart to activate, vanilla-shaped
terrain).** For presets that ask for a non-vanilla build-height range (a
world tall enough for genuinely huge trees needs more than the standard
384 blocks) or a truly alien sky/ceiling, Nexus Dimensions generates a
small datapack straight from the preset's YAML — a `dimension_type` JSON,
a `dimension` JSON pointing at it, and any custom `biome` JSON files —
and drops it in the server's datapacks folder. That dimension exists,
permanently, the next time the server boots, exactly like the Overworld,
Nether, and End do.

Here's the part worth being upfront about: **there is no supported Paper
API to attach our own `ChunkGenerator` to a dimension that vanilla itself
auto-creates from a datapack.** `WorldCreator#generator()` only works for
worlds *your plugin* creates through `WorldCreator`, which is limited to
the three built-in `World.Environment` values and their fixed height
ranges. A datapack-defined dimension is booted by the server itself, and
by the time your plugin sees it as a `World`, its terrain generator is
already fixed to whatever the `dimension` JSON's `generator` block said
(we use vanilla's `minecraft:flat` there, so it comes in as a clean,
mostly-empty platform at a config-chosen height). Anyone who tells you a
Paper plugin can bolt fully custom noise terrain onto a fully custom
`DimensionType` in one step is glossing over this — it's exactly why
"planet mod" projects that need both at once are usually built on
Forge/Fabric, which have raw mixin/NMS access Paper's plugin API doesn't
expose.

So Tier 2 buys you real custom height and real custom sky/ceiling and
real custom biomes, on a blank canvas; **giant trees and other flora
still get placed on top of it** by Nexus Dimensions — just through a
world-generic `ChunkLoadEvent` listener instead of a `ChunkGenerator`
populator, reusing the exact same tree-shaping code (see section 5). What
you don't get in Tier 2 is our crater/noise/ocean *terrain* shaping —
that's a Tier 1 feature, because Tier 1 is the one place we're actually
handed the generator.

The config format is unified — you always write one preset YAML file per
dimension — but a `worldHeight` block is the signal that a preset needs
Tier 2 and a restart, and it changes what kind of terrain you get.
Everything else is instant and keeps our full noise-based terrain.

**Gravity** is not data-driven in vanilla at all; it's hardcoded client
physics. There is no legitimate server-side way to change fall
acceleration. Nexus Dimensions fakes "low gravity" per world by damping
downward velocity and reducing fall damage in a tick listener — good
enough to feel floaty, not a real physics change. This is called out
explicitly in the preset schema (`lowGravity`) so it's never presented as
more than it is.

## 2. Architecture

```
NexusDimensionsPlugin
 ├─ config/
 │   ├─ DimensionPreset          – parsed, validated in-memory model of one preset
 │   └─ PresetLoader             – reads presets/*.yml → Map<id, DimensionPreset>
 ├─ generation/
 │   ├─ noise/NoiseUtil          – seeded Perlin, fBm, ridged fBm, 2D/3D Worley
 │   ├─ NexusChunkGenerator      – Tier 1 terrain: height/density, layering,
 │   │                             caves, craters, palette variants/deposits
 │   ├─ TerrainHeightSampler /
 │   │   Density3DSampler        – GroundHeightSource: heightmap vs true-3D terrain
 │   ├─ NexusBiomeProvider       – per-column biome selection (single or blended)
 │   ├─ TreeShaper /
 │   │   GiantTreePopulator      – shared tree algorithm + Tier 1 chunk-safe placement
 │   ├─ TreeSpeciesPicker        – weighted per-anchor species choice
 │   ├─ DecorationShaper /
 │   │   DecorationPopulator /
 │   │   DecorationSpeciesPicker – ground clutter: boulders, crystals, floating debris
 │   ├─ StructurePopulator       – Tier 1 blueprint placement (+ loot enqueue)
 │   └─ DeterministicHash        – shared per-chunk/per-block seeded hash
 ├─ structure/
 │   ├─ Blueprint / BlueprintLoader – hand-authored block-list structures
 │   └─ BlueprintTransform       – rotation/mirror position transform
 ├─ datapack/
 │   └─ DatapackGenerator        – emits dimension_type / dimension / biome JSON
 ├─ world/
 │   ├─ DimensionManager         – creates/tracks worlds, applies preset "flavor"
 │   ├─ GravityService           – JUMP_BOOST/SLOW_FALLING/jump-cancel "gravity"
 │   ├─ AmbientParticleService   – config-driven particle "weather" + wind push
 │   ├─ SeasonService            – cosmetic v1 season-stage cycling
 │   ├─ NexusFloraListener /
 │   │   NexusDecorationListener /
 │   │   NexusStructureListener  – Tier 2 counterparts of the generation/ populators
 │   ├─ PortalManager /
 │   │   PortalListener          – vanilla NETHER_PORTAL-based dimension links
 │   ├─ MobCustomizationListener – vanilla-mob attribute/equipment/name reskins
 │   └─ StructureLootService     – off-thread-safe chest → real loot table queue
 └─ command/
     └─ NexusDimCommand          – /nexusdim list|create|tp|reload|portal
```

## 3. Preset schema (`plugins/NexusDimensions/presets/<id>.yml`)

```yaml
id: ice_moon
displayName: "Frozen Moon"

# Tier selector -----------------------------------------------------------
# Omit worldHeight/sky/customBiomes entirely for an instant Tier 1 world.
#
# Full control (Tier 2): a custom height range and/or a full custom
# gameplay ruleset. effects must be one of vanilla's three actual values:
# minecraft:overworld / minecraft:the_nether / minecraft:the_end.
worldHeight:
  minY: -256
  height: 1024           # minY + height must be <= 2032, both multiples of 16
  hasCeiling: false
  hasSkylight: true
  ambientLight: 0.05
  effects: minecraft:the_end
  fixedTime: 18000           # optional, omit for normal day/night cycle
  # Gameplay flags - safe Overworld-like defaults regardless of `effects`,
  # override explicitly if you want the full themed ruleset bundled too:
  ultrawarm: false
  natural: true
  piglinSafe: false
  bedWorks: true
  respawnAnchorWorks: false
  hasRaids: true

# Shorthand alternative to worldHeight: "just give me a different vanilla
# sky at standard 384-block height," nothing else custom. Still Tier 2.
# Mutually exclusive with worldHeight in practice - worldHeight wins if
# both are present.
# sky:
#   effects: minecraft:the_end

environment: THE_END       # Tier 1 fallback / base vanilla behavior set (NORMAL, NETHER, THE_END)
seed: 8675309               # optional; random if omitted

terrain:
  seaLevel: 40
  baseHeight: 48
  heightVariation: 90
  noise:
    frequency: 0.006
    octaves: 5
    lacunarity: 2.1
    gain: 0.5
    ridged: true            # abs(noise) -> sharp jagged terrain, good for crags
    warp: 0.35               # domain-warp strength, 0 = off
  craters:
    enabled: true
    frequency: 0.015
    depth: 22
    rimHeight: 6
    jitter: 0.8
  caves:
    enabled: true
    frequency: 0.02
    threshold: 0.62

palette:
  surfaceBlock: minecraft:packed_ice
  subsurfaceBlock: minecraft:blue_ice
  subsurfaceDepth: 4
  deepBlock: minecraft:stone
  liquidBlock: minecraft:water     # set to air / lava / custom for other worlds
  liquidLevel: -1                   # -1 = use terrain.seaLevel, otherwise override

biomes:
  mode: single                      # single | blended
  entries:
    - id: minecraft:snowy_slopes
      weight: 1

customBiomes: []          # list of nexus:xxx datapack biome definitions (Tier 2 only, see below)

trees:
  enabled: false

# Ambient particle "weather" - see DESIGN.md section 8. Any org.bukkit.Particle
# name works; DUST additionally reads `color`.
particles:
  enabled: true
  type: minecraft:snowflake
  color: "0xE8F4FF"        # only used for DUST / DUST_COLOR_TRANSITION
  density: 18                # particles per player per batch
  radius: 12                 # horizontal scatter radius around the player
  heightSpread: 8            # vertical scatter around eye height
  intervalTicks: 4           # batches this often
  windStrength: 0.03         # 0 = pure visual; >0 also gently nudges players (capped, subtle)

flavor:
  gravity: 0.35             # 1.0 = vanilla; <1 = floatier (moons); >1 = heavier
  # allowJumping: false      # optional override; auto-computed from gravity if omitted
  alwaysClearWeather: true
  generateStructures: false
  generateDecorations: false
  generateVanillaCaves: false
```

A "wild" preset (`sky_forest.yml`) additionally uses:

```yaml
worldHeight:
  minY: -64
  height: 2032               # room for genuinely huge growth
  hasCeiling: false
  hasSkylight: true
  ambientLight: 1.0
  effects: minecraft:overworld

customBiomes:
  - id: nexus:violet_canopy
    category: forest
    temperature: 0.6
    downfall: 0.8
    skyColor: "0x8A5CF6"
    fogColor: "0xC9A8FF"
    waterColor: "0x5C2E8A"
    waterFogColor: "0x3A1A5C"

trees:
  enabled: true
  rarityPerChunk: 0.02       # ~1 in 50 chunks spawns an anchor for a giant tree
  # minHeight/maxHeight/canopyRadius/trunkBlock/leafBlock/giantCanopyLayers
  # here are the single-species fallback, used only if `species` is empty.
  minHeight: 220
  maxHeight: 400
  canopyRadius: 26
  trunkBlock: minecraft:dark_oak_log
  leafBlock: minecraft:azalea_leaves
  giantCanopyLayers: 14
  # Optional weighted multi-species palette - each tree anchor chunk picks
  # one of these (weighted random, deterministic) instead of every tree in
  # the dimension being identical. See DESIGN.md section 5.
  species:
    - name: "Violet Titan"
      weight: 3
      minHeight: 260
      maxHeight: 400
      canopyRadius: 28
      trunkBlock: minecraft:dark_oak_log
      leafBlock: minecraft:azalea_leaves
      giantCanopyLayers: 16
    - name: "Ashen Spire"
      weight: 2
      minHeight: 220
      maxHeight: 300
      canopyRadius: 20
      trunkBlock: minecraft:birch_log
      leafBlock: minecraft:flowering_azalea_leaves
      giantCanopyLayers: 12
```

`structures:`, `decorations:`, `creatures:`, and `seasons:` blocks aren't
shown in the examples above to keep them from ballooning further — their
exact schema is documented alongside the systems that read them:
structures/blueprints (including `randomRotation`/`randomMirror`) in
section 9, ground decorations in section 5c, mob reskinning in section 11,
seasons in section 12. Same reasoning for `caves.mode`/
`terrain.density3d.shape`/`terrain.density3d.liquids`/`palette.variants`/
`palette.glowDeposits` (section 4c) and the tree organic-shape/accent
fields (section 5b) — `crystal_spires.yml` and `sky_forest.yml`'s species
lists are the fullest worked examples of those, and `moon.yml`/
`crystal_spires.yml`/`sky_forest.yml`/`floating_isles.yml` between them
cover every `decorations.species` field at least once.

## 4. Terrain generation

`NexusChunkGenerator` never delegates to vanilla noise. Per column it:

1. Samples fractal Perlin noise (`NoiseUtil.fbm2D`, optionally ridged and
   domain-warped) to get a base height around `terrain.baseHeight` ±
   `terrain.heightVariation`.
2. If `craters.enabled`, subtracts a Worley/cellular bowl field so terrain
   gets pitted with crater bowls + raised rims — this is what makes moons
   read as moons instead of hilly Overworld clones.
3. Fills the column: `deepBlock` from bedrock/minY up, `subsurfaceBlock`
   for `subsurfaceDepth` blocks, then `surfaceBlock` on top; anything below
   `liquidLevel` and above terrain height is filled with `liquidBlock`
   (this is how "full ocean planet" works — set `liquidLevel` far above
   `baseHeight` and most of the world is submerged).
4. If `caves.enabled`, carves a 3D fbm noise threshold through the solid
   column instead of relying on vanilla cave carvers.

`NexusBiomeProvider` assigns biomes per column: `mode: single` always
returns one biome (simplest, matches "ice moon = snowy_slopes
everywhere"); `mode: blended` picks from a weighted list using a slow
low-frequency noise field, letting one dimension host a handful of
custom moods without becoming a vanilla-style biome patchwork.

## 4b. True 3D terrain (`terrain.mode: density3d`)

Section 4's heightmap approach — one height value per (x, z) column —
structurally cannot produce an overhang, an arch, or a floating island.
There's exactly one surface per column, always, no matter how the noise
driving that height is tuned. Getting those requires evaluating
solid-or-air per **block** (x, y, z), not per column — that's what
`terrain.mode: density3d` switches on, implemented in `Density3DSampler`.

The formula: 3D fractal noise (`NoiseUtil.fbm3D`), minus a penalty that
grows with (squared) distance from the nearest configured altitude
**band**'s center. A block is solid where `density > threshold`. One wide
band looks like ordinary ground with occasional overhangs where the noise
dips low near the surface. Several narrow bands at different `center`
heights read as distinct floating island layers, because the penalty term
pins each band's noise-band toward solid near its center and toward air
away from it — the same noise carves caves through solid mass and, if
locally strong enough, stray floating chunks outside the bands entirely.
One formula, no separate crater/cave pass (heightmap-only concepts are
ignored in this mode; `PresetLoader` warns if `craters.enabled` is set
alongside it).

`threshold` is genuinely a tuning knob, not something with an obviously
correct value — it trades off "solid island cores" against "swiss-cheese
porosity" for the exact same noise. `floating_isles.yml`'s `-0.1` wasn't
guessed: it came from numerically sampling `density()` across a grid at
each band's center Y using the preset's actual noise/band parameters and
picking the threshold that landed around 65-70% solid at each core with
clean (near-0%) air gaps between layers — the same verification approach
as the noise falloff check mentioned in the README. If you write a new
`density3d` preset with different `noise.frequency`/`octaves`/band
`thickness` values, re-run that kind of sweep rather than assuming
`-0.1` transfers — it won't, the right threshold shifts with every other
parameter in the formula.

Surface/subsurface/deep classification checks face-adjacent density
samples (a block is "surface" if any of its 6 neighbors is air) since
exposure can now come from any direction — the underside of an overhang,
a cave wall, the bottom of a floating island — not just "from above."
That costs extra density evaluations per block; blocks whose density
isn't within `MARGIN` of the threshold skip the neighbor checks entirely
and are classified `DEEP` directly, since deep interior mass is the
overwhelming majority of solid blocks in practice and definitely isn't
near a face.

One thing this mode does **not** do, cut deliberately rather than guessed
at: it's Tier 1 only — Tier 2's datapack-backed worlds always use vanilla
flat generation regardless of `terrain.mode` (see section 6).
`GiantTreePopulator` works unchanged in this mode: it depends on
`GroundHeightSource`, an interface both `TerrainHeightSampler`
(heightmap) and `Density3DSampler` implement, so the same tree code roots
on flat ground or off the top of a floating island without knowing which.

Liquid support (`terrain.density3d.liquids`, opt-in, off by default) is
covered in section 4c below — it used to be unconditionally absent in
this mode; it's now a deliberately simplified version of the real thing
rather than still nothing at all.

## 4c. Wilder terrain: cellular caves, spire shapes, palette variants, glow deposits

Four small, independent additions, all reusing the noise machinery already
in `NoiseUtil` rather than adding new dependencies, all numerically tuned
the same way `floating_isles`' density3d threshold was — sample the actual
formula across a grid with the real defaults, check the resulting
solid/carved fraction is in a sane range, and record the sample script's
findings here instead of guessing:

**Cellular caves** (`caves.mode: cellular`, heightmap terrain only). The
original cave carving is a threshold on 3D fBm - smooth, fairly uniform
porosity. `NoiseUtil.worley3D` (new: the existing 2D Worley/cellular noise
used for craters, extended to a third axis) gives a different, distinctly
more organic result when used the same way: carve where the 3D distance to
the nearest jittered cell point is below `cellularThreshold`. That reads as
a network of roughly egg-shaped chambers, larger near each jittered point
and connecting where two chambers' territories meet, rather than a uniform
sponge. Sampled across 6000 random points at the defaults
(`cellularThreshold: 0.32`, `cellularJitter: 0.9`): ~13-18% of space
carved, comparable overall density to a typical `noise`-mode preset, and
that fraction held steady across a 0.03-0.08 frequency sweep (Worley
distance is scale-invariant; frequency mainly changes chamber *size*, not
carved fraction).

**Spire terrain shapes** (`terrain.density3d.shape: spires`, density3d
mode only). Bands (section 4b) penalize by vertical distance from a
center, producing horizontal island layers. Spires add a second, additive
term: `NoiseUtil.worley2D` (already used for craters) gives a 2D "distance
to nearest jittered cell point," and the closer a column is to one of
those points, the bigger a density bonus it gets, tapering to zero by
roughly `spireCoreFraction` of a cell width. That pushes solid mass up
into thin persistent columns right at each cell's jittered center while
the space between columns stays governed by the plain band formula (mostly
below threshold, i.e. air, if the band itself is tuned thin). At the
numerically-tuned defaults (`spireFrequency: 0.02`, `spireJitter: 0.9`,
`spireCoreFraction: 0.18`, `spireStrength: 2.5`, sampled with
`threshold: 0.0`): spire cores sampled ~67% solid across a wide vertical
range spanning well outside the underlying band's own thickness, while the
gaps between them sampled ~3% solid — a clear forest of alien rock needles
rather than a blobby mess. Like every density3d threshold, these numbers
are tied to the specific noise/band parameters they were sampled against;
re-tune for a substantially different preset rather than assuming they
transfer.

**Palette variants** (`palette.variants`, both terrain modes). Patches of
an alternate surface/subsurface block bleeding through the primary palette
in organic blobs — mineral veins, crystal patches, color variation —
instead of every block of a layer being identical. Each variant is checked
in list order against its own offset 2D fBm field (`NexusChunkGenerator`
offsets each variant's sample coordinates by a large per-index constant so
multiple variants, and the terrain height noise itself, don't correlate);
the first one whose noise exceeds its `threshold` wins that column,
otherwise the primary block is used. `threshold` is a raw fBm value, not a
literal coverage percentage — fBm output clusters near 0 (it's a sum of
octaves, so it's roughly bell-curve-shaped) rather than being uniform.
Sampled across 20000 points with a 3-octave fBm: threshold `0.0` ~ 50% of
columns pass, `0.1` ~ 28%, `0.15` ~ 19%, `0.2` ~ 12%, `0.3` ~ 4%. The
default (`0.15`) lands around "occasional patches," not "half the world."

**Glow deposits** (`palette.glowDeposits`, both terrain modes). The same
idea applied to deep (non-surface/subsurface) terrain in 3D instead of a
2D column check — small clustered pockets of a light-emitting or otherwise
eye-catching block (glowstone, shroomlight, sea lanterns, amethyst
clusters, anything), an ore-vein-style mechanic purely for atmosphere.
Uses a 2-octave 3D fBm (cheaper than the general-purpose one, evaluated
per deep block so cost matters more here) with the same non-literal
`threshold` caveat: sampled across 20000 points, `0.3` ~ 7% of deep blocks
pass, `0.4` ~ 2%, `0.5` ~ 0.5%. The default (`0.42`) lands around "rare
pockets you stumble into while caving," not "an ore stratum."

**Density3D liquids** (`terrain.density3d.liquids`, density3d mode only,
off by default). Section 4b originally left this out entirely rather than
guess at it; it's now implemented, with the corner cut stated up front
instead of hidden: `NexusChunkGenerator.generateDensity3D`'s `AIR` branch
fills with `palette.liquidBlock` whenever `y <= liquidLevel` (`liquidLevel`
resolves the same way it always has — `palette.liquidLevel` if set,
otherwise `terrain.seaLevel`). That's a flat per-block Y threshold, not a
connected-component flood fill. For a shape like "an ocean far below a
layer of floating islands" (see `floating_isles.yml`) that's not a
compromise at all — there's genuinely nothing but open air below the
lowest band, so every AIR block down there filling in reads as exactly the
single contiguous ocean a real flood fill would also produce, just without
doing the expensive connectivity search to prove it. It stops being "free"
the moment there's solid mass at multiple altitudes near the same Y (a
mid-height island with an air pocket inside a spire at a similar Y to your
intended sea level) — that pocket would flood too, independently, with no
awareness it isn't connected to the "real" sea. Pick `liquidLevel` well
clear of any band/spire's own vertical range if you want a single clean
ocean plane; if you want a lake sitting on top of one specific island
instead, this feature is the wrong tool — hand-place water there, or wait
for the real flood-fill version.

## 5c. Non-tree ground decorations

`decorations:` places small clutter — boulders, crystal shards, fungal
caps, hovering debris — that's meant to read as common background texture
rather than a rare landmark, which is why it doesn't reuse the tree/
structure "one anchor per chunk" model at all. `DecorationPopulator`
(Tier 1) and `NexusDecorationListener` (Tier 2) instead roll up to
`decorations.perChunkAttempts` independent placement candidates per chunk,
each passing or failing its own `decorations.chancePerAttempt` coin flip
on its own deterministic-hash salt channel (900, 910, 920, ... — one block
of six salts per attempt, so a preset with the default three attempts uses
900-928 and never collides with trees' 1-5, structures' 20-22, or tree
accents' 700-800+). A chunk can end up with zero, one, or several
decorations as a result, instead of exactly zero-or-one.

Every decoration is built from one shape, `DecorationShaper.place`, shared
by both tiers the same way `TreeShaper.place` is: a straight vertical
column of `species.block`, `minHeight`-to-`maxHeight` blocks tall
(deterministic per attempt, same height-roll pattern as tree trunks), and
an optional cap of `species.capBlock` on top — either a single block
(`capRadius: 0`, the default) or a small flat disc (`capRadius` > 0). That
one shape covers more ground than it sounds like: a bare boulder (height 1,
no cap), a rounded rock or mushroom cap (short column + a wide cap disc), a
crystal spike (taller column + a single-block tip), and — when
`minFloatHeight`/`maxFloatHeight` are set above 0 — alien debris hovering
off the ground entirely rather than rooted on it, which reads especially
well over `density3d` terrain where there's real open air for something to
convincingly float in (see `crystal_spires.yml`'s "Drifting Shard" and
`floating_isles.yml`'s "Broken Fragment"). `species` is a weighted list,
picked per attempt by `DecorationSpeciesPicker` — the same weighted-random-
by-deterministic-hash approach `TreeSpeciesPicker` uses for tree species,
just on its own salt sub-channel (900 + attempt·10 + 4) so species choice
doesn't correlate with an attempt's own position or size rolls.

One naming collision worth flagging explicitly since it's easy to trip
over: `decorations:` (this feature) and `flavor.generateDecorations`
(whether *vanilla's* own decoration/feature generation pass runs on a Tier
1 world — unrelated, predates this feature, see section 4's opening list)
are two completely different switches that happen to share a word. Turning
one on or off has no effect on the other.

`moon.yml` (regolith boulders in the same palette as its terrain),
`sky_forest.yml` (bioluminescent fungal caps and moss boulders under the
canopy), `crystal_spires.yml` (amethyst growths, one hovering species), and
`floating_isles.yml` (loose stone, one hovering species) are the four
worked examples.

## 5. Giant / custom flora

`GiantTreePopulator` runs as a chunk populator. To avoid duplicate or
cut-off trees at chunk borders, it uses a deterministic hash of each
chunk's coordinates + world seed to decide whether that chunk is a "tree
anchor" (roughly `rarityPerChunk` of chunks are), then grows the tree from
that single anchor column using the modern `LimitedRegion`-based populate
API, which is explicitly designed to let a feature safely spill into
neighboring chunks. Trunk height, canopy radius/shape, and block palette
all come straight from the preset's `trees` block — there's nothing
hardcoded about "a tree" here beyond the growth algorithm itself, so the
same code produces a 400-block dark-oak/azalea giant or a 30-block
stubby ice-moon shrub depending only on config.

A standard-height Tier 1 world spans 384 blocks (`y=-64` to `y=319`),
which is already enough room for genuinely huge trees — a few hundred
blocks, rooted anywhere near the bottom of that range. Only if you want
more vertical room than that (or your `baseHeight` sits high enough that
384 total blocks doesn't leave enough headroom above it) do you need a
Tier 2 `worldHeight` block. The loader checks `trees.maxHeight` against
the resolved world height at load time and logs a warning — not a hard
failure — if a tree can't fit, so you find out from the console instead
of silently getting truncated trees.

The placement math itself (anchor selection, trunk height roll, tapered
canopy radius per layer) lives in one shared `TreeShaper` helper used by
two different call sites, because Tier 1 and Tier 2 worlds hand a plugin
their terrain very differently:

- **Tier 1**: `GiantTreePopulator` runs as a `BlockPopulator` attached to
  our own `ChunkGenerator`, writing through the `LimitedRegion` the engine
  hands it during chunk generation, and asks `TerrainHeightSampler` for
  ground height (the same noise-derived height the terrain itself used).
- **Tier 2**: `NexusFloraListener` listens for `ChunkLoadEvent` with
  `isNewChunk() == true` on any world Nexus Dimensions is tracking as
  Tier 2, and writes directly through `World#getBlockAt` after asking the
  world itself for ground height via `World#getHighestBlockYAt`, since
  Tier 2 terrain is vanilla flat generation, not ours.

`TreeShaper.place` takes a single `DimensionPreset.TreeSpecies` (shape,
block palette, and every field described in section 5b below) rather than
a whole preset object, which is what makes weighted multi-species palettes
possible without either call site needing special-casing: `trees.species`,
if configured, gives a list of named species, each with its own `weight`.
`TreeSpeciesPicker.pick` rolls a weighted, deterministic choice per anchor
chunk (its own hash salt, 5, distinct from the anchor/position/height-roll
salts 1-4 so species choice doesn't correlate with where or how tall the
tree ends up) and both `GiantTreePopulator` and `NexusFloraListener` cache
each species' resolved `BlockData` by name so a recurring species isn't
re-resolved from a `Material` lookup on every placement. An empty
`species` list (the original schema, still the default) falls back to one
implicit species built straight from `trees`'s own top-level fields —
every preset written before this feature existed keeps behaving exactly
as it did.

## 5b. Organic shape and alien accents

Five more things layered onto the same trunk-and-canopy algorithm, all
per-species, all deterministic (a mix of `DeterministicHash` for scattered
choices and `NoiseUtil.perlin3` for smooth continuous ones, so an
unexplored chunk always regrows byte-identical after a restart):

- **Organic canopy silhouette** (always on, no config). The original
  canopy was a perfect tapered-radius sphere/cone per layer. Each
  candidate leaf position now also samples smooth 3D Perlin noise and
  perturbs that layer's effective radius by up to ±30% in that direction,
  which breaks the geometric roundness into something that reads as a
  real, lumpy tree canopy instead of a procedural primitive.
- **Buttress roots** (`buttressRoots`, default on). A short flare of trunk
  blocks radiating outward in eight directions from the base, 2-4 blocks
  long per direction (rolled independently so the flare itself is
  irregular) — the wide root-flare real giant trees actually have at
  ground level, which a plain vertical trunk column never suggested.
- **Branches** (`branches`, default on, trees ≥18 blocks tall only). 1-3
  secondary limbs peel off the trunk between 45-80% of its height, walk
  outward and upward at a shallow angle for 25-50% of the trunk's height,
  and end in a small mini-canopy blob of their own. Cheaper than the main
  canopy algorithm on purpose (no wobble/accents/vines) so branch count
  doesn't become a per-tree cost blowup on a rarity-tuned giant tree.
- **Trunk/canopy accents** (`trunkAccentBlock`/`canopyAccentBlock` +
  their `*Chance` fields, null/off by default). Any block key at all,
  occasionally substituted for a trunk or leaf block as it's placed —
  `minecraft:shroomlight` or `minecraft:sea_lantern` for bioluminescence,
  `minecraft:amethyst_cluster` for crystal growths, anything else for
  "fruit" or general texture variety. Deliberately generic rather than a
  hardcoded "glow mode" and a separate hardcoded "fruit mode," since the
  mechanism (occasionally replace this block) is identical either way and
  a config-driven block key is strictly more flexible than two special
  cases would have been.
- **Hanging vines** (`vineBlock` + `vineChance`/`vineMin/MaxLength`, null/
  off by default). Only rolled from the lower ~30% of the canopy (where
  "hanging from the underside" actually reads correctly) — a strand of
  `vineBlock` grown straight down for a random length per qualifying
  position. `minecraft:weeping_vines` is the natural fit (it already
  visually droops downward in vanilla); `minecraft:twisting_vines` or
  plain `minecraft:vine` work too.

None of this is on by default beyond the always-on canopy wobble and the
two structural upgrades (buttress roots, branches) — the accent/vine
fields are opt-in per species specifically because "every tree glows" or
"every tree drips vines" is a strong, specific aesthetic choice that
shouldn't be forced on every preset that merely enables trees. `moon.yml`
and `iron_giant_world.yml` don't touch them at all; `sky_forest.yml`'s
species opt into a mix, closer to what "make it wild" actually asked for.

## 6. Datapack generation (Tier 2)

On startup, `DatapackGenerator` scans loaded presets for a `worldHeight`
block or a `sky` shorthand (see section 3 — `sky` just synthesizes a
standard-height `WorldHeight` with different `effects`/`hasSkylight`/
`hasCeiling`/`ambientLight`) and, for each, writes (if not already
present / unchanged):

```
<level-name>/datapacks/nexus_<presetId>/
 ├─ pack.mcmeta
 └─ data/nexus/
     ├─ dimension_type/<presetId>.json
     ├─ dimension/<presetId>.json          (generator: minecraft:flat, per palette)
     └─ worldgen/biome/<biome-id>.json     (one per customBiomes entry)
```

`<level-name>` is the primary world's save folder (`Bukkit.getWorlds()
.get(0).getWorldFolder()`), which is where vanilla actually looks for
world-scoped datapacks. As covered in section 1, the `dimension` JSON's
generator is `minecraft:flat` — a clean platform at
`terrain.baseHeight`, layered with the preset's `palette` blocks and
(when `biomes.mode` is `single`) its base biome. There is deliberately no
attempt to reference our Bukkit generator from inside the datapack; that
hook doesn't exist. Restart the server after `/nexusdim create` reports
"datapack written" for the new dimension to actually appear.

`pack.mcmeta`'s `pack_format` comes from `config.yml`'s
`datapackPackFormat` (plugin-wide, not per-preset — every Tier 2 preset's
generated datapack uses the same value, which is correct since it's a
property of the server version, not the dimension). This **must** match
the exact server build you're running, or the datapack can silently fail
to activate no matter how many times you restart — this number changes
with almost every Minecraft point release. Default is 61 (Minecraft
1.21.4, what this plugin currently targets — see `pom.xml`); if you're on
a different point release, look up the right value at
https://minecraft.wiki/w/Pack_format and change `config.yml`, then
restart (Tier 2 datapacks are rewritten with the current config value on
every boot, per `NexusDimensionsPlugin.onEnable`, so a config edit plus
one restart is all it takes — no rebuild needed). This used to be a
hardcoded constant fixed at 48 (1.21/1.21.1's value) instead of a config
value, which was a genuine bug, not just an unverified guess: it was
wrong for the exact 1.21.4 target this project otherwise builds against,
and is exactly the kind of mismatch that produces "wrote the datapack,
restarted, dimension still doesn't appear" with no other symptom.

`WorldHeight`'s gameplay flags (`ultrawarm`, `natural`, `piglinSafe`,
`bedWorks`, `respawnAnchorWorks`, `hasRaids`) are **not** auto-derived
from `effects`. Picking `minecraft:the_nether` as a look shouldn't
silently also make water evaporate or beds stop working — they default to
safe, Overworld-like values regardless of which sky you picked, and you
override any of them explicitly (see `iron_giant_world.yml`) if you
specifically want the bundled themed ruleset too.

## 7. Gravity

Built entirely on real vanilla mechanics on purpose — see
`GravityService` for the implementation. A single `flavor.gravity` value
(1.0 = normal) drives everything:

- **Below 1.0** (moons: 0.2-0.4 is a good range): a scaled `JUMP_BOOST`
  amplifier for higher leaps, plus `SLOW_FALLING`, which both slows the
  descent *and* natively zeroes fall damage in vanilla — no separate
  damage-scaling code needed. This is also what gives you "jump and glide
  a few blocks": `JUMP_BOOST` extends the jump arc, `SLOW_FALLING` extends
  how long you're airborne to carry horizontal momentum through it. There
  is deliberately no hand-rolled horizontal-velocity-injection system
  layered on top — see the note below on why.
- **Above 1.0** (heavy: 1.5-2.5 "hard to move," 2.5+ "grounded"): a
  negative `JUMP_BOOST` amplifier for a shorter hop. Past
  `GROUNDED_THRESHOLD` (2.5 by default), jumping is disabled outright by
  cancelling Paper's `PlayerJumpEvent` — a real, certain mechanism, rather
  than trusting an extreme negative amplifier to zero out jump height
  reliably. `flavor.allowJumping` overrides the auto threshold either
  direction if you want heavy-but-still-jumpable or light-but-grounded.

**Why not hand-modify velocity every tick?** An early version of this
plugin did exactly that (damping `Player#getVelocity()` while falling).
It's the more obviously "custom physics" approach, but it fights the
client's own movement prediction — the client is simulating gravity on
its own between server updates, and overwriting velocity from the server
side every tick reads as rubber-banding on anything other than a LAN
connection. Vanilla potion effects don't have this problem because the
client already knows how to predict and render them smoothly; that's why
`GravityService` is built on `JUMP_BOOST`/`SLOW_FALLING`/event
cancellation instead. The tradeoff: gravity here is discrete steps
(amplifier levels, a jump on/off switch), not a continuously-tunable
physics constant. In practice the jump-arc + slow-fall combination gets
close enough to "float five blocks after a jump" that it's very unlikely
to be the part of this that needs revisiting first.

## 8. Ambient particles ("wind," dust storms, weather flavor)

`AmbientParticleService` spawns a config-driven batch of particles around
each player every `particles.intervalTicks`, scattered within
`particles.radius`/`heightSpread` of their eye position, using whatever
vanilla `org.bukkit.Particle` the preset names (`ASH`, `SNOWFLAKE`,
`SPORE_BLOSSOM_AIR`, `DUST` with a custom color, anything the enum has) —
no resource pack, no custom rendering, same reasoning as section 0: this
is all stuff Minecraft already ships.

`particles.windStrength` (0 by default) additionally nudges players'
horizontal velocity a small, hard-capped amount in a shared direction
that slowly rotates over about 10 minutes — enough to read as "the storm
is pushing you," not enough to fling anyone anywhere. Same rationale as
gravity: the cap exists because a strong repeated velocity write fights
client-side prediction, so this stays subtle by design rather than trying
to be a full wind-physics system.

This is a first pass at the "dust storm"/"seasons" idea — genuinely happy
to build a fuller seasonal system (presets that swap palettes/particles on
a schedule) as a follow-up once there's a clearer spec for how far to take
it; that's a bigger architectural change (it touches *already-generated*
terrain, not just new chunks) worth scoping deliberately rather than
guessing at.

## 9. Structures / blueprints

A blueprint (`blueprints/<name>.yml`) is a deliberately simple hand-authored
block list — `{dx, dy, dz, block, loot}` entries relative to an origin —
not a schematic/NBT/WorldEdit import. That's a real tradeoff: no in-game
structure editor, every block typed out by hand or generated by a small
script. In exchange it's plain YAML anyone can read, diff, and hand-edit,
with zero extra file formats or parsing dependencies. `BlueprintLoader`
reads every `blueprints/*.yml` in the data folder at startup and on
`/nexusdim reload`, log-and-skip on a bad file (same pattern as
`PresetLoader`).

`structures.randomRotation`/`structures.randomMirror` (both off by
default) give up to 8 distinct orientations of the same blueprint instead
of every copy facing the same way — `BlueprintTransform.apply` rotates
(and optionally mirrors) each block's `(dx, dz)` around the vertical axis,
picked once per anchor chunk on its own deterministic-hash salt channel
(23-24, distinct from the anchor-position salts 20-22) so a regenerated
chunk still gets the same orientation. The explicit limit: this transforms
block *positions* only, not the placed block's own orientation state
(stairs facing, log axis, chest facing, ...) — a directional block moved
to a new position still faces whichever way it was authored facing. That's
genuinely fine for a blueprint built from non-directional blocks (stone
variants, wool, glass, and — worth calling out specifically — the bundled
`ruin_small`, which is why `iron_giant_world.yml` turns both flags on) and
genuinely wrong for one built from stairs, logs, or anything else with a
facing that matters. Full per-`BlockData`-subtype re-orientation
(`Directional`/`Orientable`/`Rotatable`/`Bisected`/...) is a real, larger
feature, left for later rather than half-implemented here.

Placement uses the exact same deterministic-anchor-chunk approach as giant
trees (`DeterministicHash.hash01`, section 5), on separate salt channels
(20–22) so structures and trees never correlate: for each chunk, one hash
roll against `structures.rarityPerChunk` decides whether this chunk gets a
structure at all, then two more pick a pseudo-random local (x, z) inside it.
The structure is placed relative to that column's ground height (from the
same `GroundHeightSource` abstraction terrain/trees use, so it works
correctly under both heightmap and `density3d` terrain — including not
placing at all when a `density3d` column has no ground, e.g. a gap between
floating islands).

Tier 1 and Tier 2 need two separate placement paths, same reason as trees:
`StructurePopulator` is a `BlockPopulator` Bukkit attaches via
`ChunkGenerator.getDefaultPopulators()`, which only Tier 1 worlds (worlds we
hand our own `ChunkGenerator` to) ever get. Tier 2 worlds are auto-loaded by
vanilla from the datapack with no `ChunkGenerator` in the picture at all, so
`NexusStructureListener` does the equivalent placement directly through the
live `World` API on `ChunkLoadEvent` instead — the same split as
`NexusFloraListener` versus `GiantTreePopulator`.

A `loot: true` block entry gets placed as a real `CHEST` and its coordinates
enqueued into `StructureLootService`, which assigns a real
`org.bukkit.loot.LootTables` value on the next main-thread drain (every 10
ticks). This queue exists because Tier 1 placement can happen off the main
thread (`isParallelCapable() == true`) where only `LimitedRegion`'s raw
block-data API is safe to touch — a `Lootable` `BlockState` is live-world
API and must be touched from the main thread, so the populator only ever
enqueues a coordinate + loot-table name, never touches `Lootable` itself.

## 10. Portals

Rather than invent a custom portal frame block or particle effect, Nexus
Dimensions links real vanilla `NETHER_PORTAL` blocks — built and lit by a
player exactly as vanilla intends, with obsidian and flint and steel. That
gets genuine vanilla portal animation, ambient sound, and particles for
free, with zero resource pack, which is the same "use what Minecraft
already ships" reasoning behind the sky/gravity/particle systems.

`/nexusdim portal link <destinationWorld> [destX destY destZ]`, run while
standing near a lit portal, has `PortalManager` search a small radius for a
`NETHER_PORTAL` block, flood-fill every 6-connected portal block from there
(capped at 4096 blocks as a sanity limit — a real portal is a few dozen),
and register the resulting bounding box in `portals.yml` alongside a
destination: either exact coordinates, or (if none given) the destination
world's spawn, resolved lazily so it still works if that world's spawn
changes later. `/nexusdim portal unlink` removes the registration without
touching the physical blocks; `/nexusdim portal list` shows every
registered link.

`PortalListener` handles `PlayerPortalEvent`, checks whether the player's
`from` location falls inside any registered portal's bounding box (with a
1-block pad, since players trigger the event slightly inside the frame, not
exactly on a portal block), and if so calls `event.setTo(...)` with the
resolved destination instead of letting vanilla search for or create an
Overworld/Nether pairing. If a portal is registered but its destination
world isn't currently loaded, the teleport is cancelled with a message
rather than silently dumping the player into a freshly generated fallback
world. See the class javadoc for the caveat around `setSearchRadius` /
`setCreationRadius` / `setCanCreatePortal` — real `setTo()` redirection
works regardless of those; they're a best-effort belt-and-suspenders
against vanilla also creating a *second*, unlinked portal at the
destination.

One deliberate scope limit remains: only one destination per portal — a
portal always redirects to exactly one place, it can't fan out. Linking a
return trip, though, no longer has to be a fully separate manual step:
`/nexusdim portal link <destinationWorld> [destX destY destZ] both` also
searches near the destination (the given coordinates, or that world's
spawn if none were given) for an existing lit portal, and if one's there,
registers it pointing back at the portal that was just linked —
`NexusDimCommand.attemptAutoReturnLink` reuses the exact same
`PortalManager.linkNearby` search the forward link already does, just
aimed at the other side. This is deliberately best-effort, not "create one
if missing": the plugin doesn't place blocks in a player's world
unprompted, so if there's no lit portal within a few blocks of the
destination, the command says so and asks the player to build one and link
it manually — exactly the one-directional-by-default behavior from before
this flag existed, just no longer the *only* option. Omit `both` (or link
without it, as before) for the original one-directional behavior.

## 11. Custom mobs

No genuinely new mob species — that needs an NMS-level custom entity
class, off the table for a plugin built entirely on public Bukkit/Paper
API (see section 1). What *is* on the table, and what `creatures:` gives
you per preset: real vanilla mobs with edited attributes (health, speed,
melee damage, and — on 1.20.5+ — visual size via `Attribute.GENERIC_SCALE`),
a forced equipment loadout with drop chance zeroed out, a custom name, a
glow, and thinned (never boosted) natural spawn frequency.

`MobCustomizationListener` hooks `CreatureSpawnEvent`. Two independent
things happen there, in order: first, if the preset's `spawnMultiplier` is
below 1.0 and this was a `NATURAL`-reason spawn, a weighted coin flip may
cancel it outright — this is the *only* direction that's actually
supported. There is no legitimate Bukkit hook to make vanilla attempt
natural spawns *more* often than it already decided to; `PresetLoader`
clamps any `spawnMultiplier` above 1.0 back down to 1.0 and logs a warning
rather than silently no-op'ing. Second, if the preset has a `creatures.mobs`
entry matching this entity's type, its attribute/equipment/name/glow
overrides are applied.

A deliberate limit worth being explicit about: this system does not decide
*whether* a given mob type is eligible to spawn in the first place — that
is still entirely vanilla's own per-biome `MobSpawnSettings` and
per-dimension mob-category caps, unaffected by anything here. Listing a
`creatures.mobs` entry for a type the current biome never naturally
produces isn't a bug, it just never fires — the preset comments for `moon`
(Endermen, the only thing `minecraft:the_end` naturally spawns) and
`iron_giant_world` (Magma Cubes/Ghasts, from `minecraft:basalt_deltas`)
call out why those specific types were picked.

Genuine future work, not attempted here: actual AI behavior changes (via
Paper's Mob Goal API — adding/removing/replacing a mob's individual
pathfinder goals) and per-dimension custom loot/spawn tables beyond what
`LootTables` already covers. Both are real, buildable features; they were
left out of this pass to keep the attribute/equipment reskin system itself
solid and reviewable rather than shipping three half-finished systems at
once.

## 12. Seasons (cosmetic v1)

A `seasons:` block defines an ordered cycle of named `stages`, each lasting
`durationTicks` real server ticks, that a dimension loops through forever.
Each stage can override three things while it's active: the ambient
particle profile (a full `particles:` block, same schema as the top-level
one), how much natural mob spawning is thinned (`spawnMultiplierOverride`),
and whether weather is forced clear or allowed to roll in
(`forceClearWeather`). Any field a stage doesn't set falls back to the
preset's normal baseline — a stage doesn't have to restate everything, only
what actually changes for it.

`SeasonService` tracks elapsed time per seasons-enabled world with a plain
in-memory tick counter, advanced once per real second (a season cycle has
no need for tick-perfect precision), and exposes `effectiveParticles` /
`effectiveSpawnMultiplier` / `effectiveAlwaysClearWeather` — the value
`AmbientParticleService`, `MobCustomizationListener`, and
`DimensionManager`'s weather listener actually read now, instead of going
straight to the preset. All three services keep working exactly as before
in a world with seasons disabled or unconfigured (`SeasonService` just
returns the preset's plain values), so this is additive, not a rewrite of
how those systems work.

Explicitly out of scope for this v1, matching the "cosmetic" label: nothing
here touches already-generated terrain. A real "winter" that reskins
placed blocks (grass under a temporary snow layer, water under temporary
ice) needs to either walk and rewrite every already-explored chunk on
every stage transition, or maintain a second block-overlay layer purely
client-side — either is a materially bigger feature than a particle/
weather/spawn-rate cycle, and doing it well needs its own design pass
rather than being bolted onto this one. Season phase also isn't persisted
across restarts (see `SeasonService`'s javadoc) — an intentional
simplicity tradeoff for a cosmetic feature where restarting mid-blizzard
and coming back to "Calm" is a total non-issue.

## 13. Commands

- `/nexusdim list` — show loaded presets and which are Tier 1 vs Tier 2.
- `/nexusdim create <name> <presetId> [seed]` — Tier 1 presets: creates and
  loads the world immediately. Tier 2 presets: generates/refreshes the
  datapack and tells the operator a restart is required, then auto-loads
  on the next boot.
- `/nexusdim tp <name>` — teleport to a created dimension's spawn.
- `/nexusdim reload` — re-read `presets/*.yml` and `blueprints/*.yml` from
  disk without a restart (does not retroactively change already-created
  worlds' block data, only affects newly created ones and in-memory flavor
  settings).
- `/nexusdim portal link <destinationWorld> [destX destY destZ] [both]` —
  see section 10; the trailing `both` also attempts a best-effort return
  link.
- `/nexusdim portal unlink|list` — see section 10.

## 14. Roadmap (not in the first scaffold)

Four items that used to live on this list are done as of this pass —
liquid/ocean support for `density3d` mode (section 4c, opt-in and
explicitly a flat-threshold simplification, not the full flood-filled
version), non-tree decoration palettes (section 5c), blueprint rotation/
mirroring (section 9, position-only), and best-effort return-portal
auto-linking (section 10, the `both` flag). What's left is left
deliberately, not for lack of a good idea — each is either a materially
bigger design effort than the four above, or reaches into API surface this
project hasn't needed to touch yet:

- **Mob AI edits** — actual pathfinder/goal changes via Paper's Mob Goal
  API (e.g. a "grounded" dimension's mobs never try to jump either), plus
  dedicated per-dimension spawn tables instead of riding vanilla's
  biome-based eligibility — see section 11's closing note. Held back
  because the Mob Goal API is a meaningfully different (and less-tested,
  in this project's own experience) corner of Paper than anything else
  here touches — worth its own careful pass rather than bolting a first,
  possibly-wrong attempt onto this one.
- **Seasons v2 (terrain-affecting)** — an actual snow/ice overlay on
  already-placed blocks during a stage, reverted on transition out. A real
  design pass, not a bolt-on — see section 12's closing note for why v1
  deliberately doesn't attempt this; it needs either a full re-walk of
  every explored chunk per transition or a second overlay layer, neither
  of which is a small addition to what's here.
- A `/nexusdim wizard` in-game preset builder that writes the YAML for
  you — a real feature, but a UX-focused one layered on top of everything
  else here rather than a generation/placement capability, so it's last.
