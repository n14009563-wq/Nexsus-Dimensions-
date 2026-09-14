# Nexus Dimensions

Config-driven custom dimensions for Paper 1.21.x with fully custom noise
terrain, giant custom flora, and (for the cases that genuinely need it) a
generated datapack for non-vanilla world height and brand-new biomes.

Read **DESIGN.md** first — it explains the two-tier architecture (instant
custom-terrain worlds vs. restart-requiring custom-height/sky worlds) and
exactly what each tier can and can't do. This isn't a "read later" doc;
the honest capability boundaries in section 1 will save you real time.

## What's in this scaffold

A complete, from-scratch Java/Maven Paper plugin project:

- `NexusChunkGenerator` / `NexusBiomeProvider` — fully custom noise-based
  terrain and biome placement, no vanilla delegation. Two terrain modes:
  `heightmap` (default, one surface per column) or `density3d` — real 3D
  terrain via `Density3DSampler`, the only way to get overhangs, arches,
  and floating islands (a heightmap structurally can't represent more
  than one surface per column, however the noise driving it is tuned) —
  see DESIGN.md section 4b. Both modes also support `caves.mode: cellular`
  (organic Worley-noise cavern networks instead of the default smooth
  carving), `terrain.density3d.shape: spires` (thin alien rock needles
  instead of horizontal island layers), `terrain.density3d.liquids`
  (opt-in liquid fill for density3d terrain — a flat Y threshold, honestly
  documented as not a full flood fill), `palette.variants` (patchy
  secondary surface/subsurface blocks), and `palette.glowDeposits`
  (clustered light-emitting/eye-catching blocks underground) — all
  numerically tuned, see DESIGN.md section 4c.
- `GiantTreePopulator` / `NexusFloraListener` — config-driven giant trees
  (or any other size), placed via two different mechanisms depending on
  which tier the world is (see DESIGN.md section 5). Root correctly in
  both terrain modes via the shared `GroundHeightSource` interface.
  Optional weighted multi-species palettes via `trees.species` —
  `TreeSpeciesPicker` picks one species per anchor chunk instead of every
  tree being identical. Every tree also gets an organic noise-perturbed
  canopy silhouette, buttress roots, and secondary branches by default,
  plus optional per-species bioluminescent/crystal accents and hanging
  vines — see DESIGN.md section 5b.
- `DecorationPopulator` / `NexusDecorationListener` — small ground-clutter
  decorations (boulders, crystal shards, fungal caps, hovering alien
  debris), placed much more densely than trees since this is meant to be
  common texture rather than a rare landmark — up to `perChunkAttempts`
  independent rolls per chunk instead of one anchor. See DESIGN.md
  section 5c.
- `PresetLoader` — parses `presets/*.yml` into validated preset objects.
- `DatapackGenerator` — writes the `dimension_type` / `dimension` / custom
  `biome` JSON for Tier 2 presets, including a borrowed vanilla sky
  (Nether/End) decoupled from that dimension's other gameplay rules. The
  data pack_format it stamps into `pack.mcmeta` comes from `config.yml`'s
  `datapackPackFormat` (default 61, for Minecraft 1.21.4) — edit that one
  value and restart if your server runs a different point release.
- `DimensionManager` — creates/tracks worlds, persists which world folder
  uses which preset so the right generator gets re-attached on restart.
- `GravityService` — per-world "gravity" (floaty moon jumps, heavy
  grounded worlds that can't jump at all) built entirely on vanilla
  `JUMP_BOOST`/`SLOW_FALLING`/jump-event cancellation — see DESIGN.md
  section 7 for why it's not hand-rolled velocity code.
- `AmbientParticleService` — config-driven ambient particles (dust storms,
  snow, spores, anything `org.bukkit.Particle` has) with an optional
  subtle "wind" push — see DESIGN.md section 8.
- `StructurePopulator` / `NexusStructureListener` / `BlueprintLoader` /
  `StructureLootService` — hand-authored YAML "blueprint" structures
  (loot-bearing ruins by default) placed via the same deterministic-anchor
  approach as giant trees, on both tiers, with real vanilla loot tables
  assigned safely off the chunk-generation thread. Optional
  `structures.randomRotation`/`randomMirror` (via `BlueprintTransform`)
  give up to 8 orientations of the same blueprint instead of always the
  same facing — position-only, see DESIGN.md section 9 for the exact
  tradeoff.
- `PortalManager` / `PortalListener` — links real vanilla `NETHER_PORTAL`
  blocks (built and lit normally) between any two Nexus dimensions, no
  custom frame block or resource pack needed. `/nexusdim portal link ...
  both` also attempts a best-effort return link on the destination side
  instead of staying one-directional by default — see DESIGN.md
  section 10.
- `MobCustomizationListener` — reskins real vanilla mobs per-dimension:
  attribute overrides (health/speed/damage/size), forced equipment, a
  custom name, a glow, and thinned natural spawn frequency — no new mob
  species (that needs NMS), just vanilla mobs made to feel native to the
  dimension they're in. See DESIGN.md section 11.
- `SeasonService` — cosmetic v1 seasons: a per-dimension cycle of named
  stages that can override ambient particles, spawn thinning, and forced-
  clear weather while active, without touching already-placed terrain —
  see DESIGN.md section 12.
- `/nexusdim` command (`list`, `create`, `tp`, `reload`, `portal
  link|unlink|list`, `portal link ... both`).
- Seven example presets: `moon` (Tier 1 — reskinned "Lunar Wraith"
  Endermen, now scattered with regolith boulders/rubble), `ice_moon`
  (Tier 1, Tier-1-only End/Overworld sky + low gravity + particles, a
  Calm/Blizzard season cycle), `ocean_planet` (Tier 1), `sky_forest`
  (Tier 2 — 400-block trees across three species with
  bioluminescent/crystal/vine accents, bioluminescent fungal-cap/moss
  ground decorations, a from-scratch biome, extended world height),
  `iron_giant_world` (Tier 2 — borrowed Nether sky *without* the bundled
  Nether ruleset, heavy grounded gravity, a real dust storm, loot-bearing
  ruins now placed with random rotation/mirroring, heavier reskinned Magma
  Cubes/Ghasts, and organic cellular caves), `floating_isles` (Tier 1,
  `density3d` mode — three genuinely floating island layers with trees and
  loose rock/hovering debris on top of them, now with a real ocean far
  below via `terrain.density3d.liquids`), and `crystal_spires` (Tier 1,
  `density3d.shape: spires` — thin alien rock needles instead of island
  layers, dark obsidian veins, glowing underground deposits, amethyst
  ground growths and hovering shards, and giant amethyst-accented trees
  rooted right on the spire tops).
- One bundled blueprint, `blueprints/ruin_small.yml` — copy it to author
  your own structures.

## Building

This is a plain Maven project — `pom.xml` is at the project root, same as
any other Maven-built Java project. (An earlier version of this scaffold
used Gradle instead; if you're looking at old instructions or a cached
copy that mentions `gradlew`, that's stale — this is Maven now, no Gradle
files are part of the project anymore.)

1. Install a JDK 21 (Maven itself doesn't need installing in most dev
   containers/Codespaces — it's commonly preinstalled; run `mvn -v` to
   check).
2. From the project root: `mvn package`. Maven needs normal outbound
   internet access the first time, to pull down the build plugins from
   Maven Central and — the one non-default repository this project
   needs — the real Paper API from `repo.papermc.io` (declared in
   `pom.xml`'s `<repositories>` block, since Paper isn't published to
   Central itself). If you're in a locked-down container or CI runner
   with restricted egress, allow `repo.maven.apache.org` and
   `repo.papermc.io` (or run it once somewhere with open internet and
   copy the populated `~/.m2/repository` cache over).
3. The plugin jar lands in `target/NexusDimensions-0.1.0.jar`.
4. Copy that jar into your Paper server's `plugins/` folder.

There's no Maven Wrapper (`mvnw`) bundled here the way some Maven projects
ship one — generating a real, working one needs network access this
sandbox doesn't have, and since your environment already has `mvn` on the
`PATH` (that's what produced the original "no POM" error — the tool was
present and working, just pointed at a project that used to be Gradle),
a wrapper isn't actually needed for you to build this. If you want one
anyway for CI reproducibility, `mvn -N wrapper:wrapper` (Maven 3.9+) will
generate it once you have working internet access.

I verified this two ways, from a sandbox with no outbound access to
either Maven Central or PaperMC's repo: first, compiling the source with
`javac` directly (no Paper API present) and confirming every resulting
error was an expected "cannot find symbol"/"package does not exist" for a
Paper class, which is exactly what disappears once the real dependency is
on the classpath. Second, actually running `mvn compile` against this
exact `pom.xml` here — Maven parsed it, resolved the project correctly,
and got as far as trying to download the `maven-resources-plugin` before
hitting this sandbox's network restriction (a 403 from the outbound
proxy, not a project problem) — so the POM itself is confirmed
mechanically sound up to the point this sandbox can't reach the internet.
It should complete cleanly the first time you run it somewhere with
normal internet access.

If anything fails to compile against the real API, it's most likely one
of these — check them first since I couldn't verify them against the
actual jar:

- `LimitedRegion`'s exact bounds-check method name in `GiantTreePopulator`
  (I used `isInRegionBounds(int, int, int)` — Paper has changed
  populator-region APIs before across versions).
- The Adventure `CommandSender#sendMessage(Component)` overload used in
  `NexusDimCommand` — solidly part of the Paper API, but worth confirming
  against whatever exact 1.21.x version you target.
- `pack_format` — this used to be a hardcoded constant in
  `DatapackGenerator` (and was actually wrong: fixed at 48, which is
  Minecraft 1.21/1.21.1's value, not 1.21.4's — a real bug, not a "check
  this against your version" caveat, found and fixed after a Tier 2
  dimension wouldn't activate even after multiple restarts). It's now
  `config.yml`'s `datapackPackFormat` (default 61, correct for 1.21.4) —
  if your server runs a different point release, edit that value instead
  of touching code; see config.yml's own comment for the lookup table.
- `PlayerJumpEvent`'s package in `GravityService` — I imported it from
  `com.destroystokyo.paper.event.player` (where it's lived since it was
  added); if that doesn't resolve, try `io.papermc.paper.event.player`
  instead.
- Negative `PotionEffect` amplifiers for the heavy-gravity jump reduction
  in `GravityService` — a known technique, but I couldn't test it against
  a real client. If jump height doesn't visibly shrink at `gravity > 1.0`,
  the guaranteed-to-work part (full jump cancellation past the grounded
  threshold) still holds regardless.
- `LimitedRegion.isInRegionBounds` is also used by the new
  `StructurePopulator` — same caveat as `GiantTreePopulator` above, same
  fix if it doesn't resolve.
- The `org.bukkit.loot.Lootable` interface cast in `StructureLootService`
  (`state instanceof Lootable lootable`) — this is the standard way to set
  a loot table on a placed chest/barrel/etc. in modern Bukkit/Paper, but if
  the interface has moved or been renamed in your target version, that's
  the one line to fix; everything else in structure placement is
  independent of it (the chest still gets placed, it would just stay
  empty).
- `PlayerPortalEvent#setSearchRadius(int)` / `setCreationRadius(int)` /
  `setCanCreatePortal(boolean)` in `PortalListener` — called defensively
  to stop vanilla from also creating an unlinked portal at the
  destination; `event.setTo(...)`, the line that actually matters for
  redirecting the teleport, is stable and not in question. If any of the
  other three don't compile, just delete that line — see the class
  javadoc.
- `Attribute.GENERIC_SCALE` in `MobCustomizationListener` (used for
  `creatures.mobs.<TYPE>.scale`) — added in 1.20.5, should be present on
  any 1.21.x API, but it's the newest attribute referenced in this
  project so worth checking first. `GENERIC_MAX_HEALTH` /
  `GENERIC_MOVEMENT_SPEED` / `GENERIC_ATTACK_DAMAGE` are long-stable and
  not in question; if only `GENERIC_SCALE` fails, delete that one `if`
  block in `applyProfile()` and every other override still works.
- Nothing new in `TreeShaper`/`NexusChunkGenerator`'s wilder-terrain
  additions reaches for an uncertain API surface — they're built entirely
  on `NoiseUtil` (self-contained, no external dependency) plus the same
  `Material`/`BlockData`/`ChunkData` calls the rest of terrain generation
  already used. If something there doesn't compile, it's more likely a
  copy-paste slip than a real API mismatch — worth a second look before
  assuming it belongs on this list.
- Same goes for this round's additions — `DecorationShaper`/
  `DecorationPopulator`/`NexusDecorationListener`/
  `DecorationSpeciesPicker` (ground decorations), `BlueprintTransform`
  (rotation/mirroring), `terrain.density3d.liquids`'s handling in
  `NexusChunkGenerator`, and `NexusDimCommand`'s `portal link ... both`
  flag are all built entirely on APIs the rest of this project already
  uses elsewhere (`Material`/`BlockData`/`ChunkData`/`LimitedRegion`/
  `World`/`Location`, `DeterministicHash`) — nothing here is new,
  uncertain surface.

## Running it

1. Start the server once so `plugins/NexusDimensions/presets/` and
   `plugins/NexusDimensions/blueprints/` get populated with the bundled
   example files.
2. In-game (as an op) or from console:
   - `/nexusdim list` — see what's loaded and which tier each preset is.
   - `/nexusdim create my_moon moon` — Tier 1, instant. You should be
     teleported into a cratered, low-gravity, void-sky moon immediately.
   - `/nexusdim create my_isles floating_isles` — Tier 1, instant. Three
     distinct floating island layers you can fall between (safely — low
     gravity + slow-falling means no fall damage).
   - `/nexusdim create my_forest sky_forest` — Tier 2. The command tells
     you it wrote a datapack and needs a restart; restart the server and
     the `Violet Sky Forest` dimension (with its from-scratch biome and a
     mix of three giant tree species — dark oak, birch, and jungle giants
     up to 400 blocks tall) will be live.
   - `/nexusdim create my_iron iron_giant_world` — Tier 2, restart
     required. Once live, explore around to find one of the `ruin_small`
     structures (rare — about 1 in 200 chunks) with a chest holding real
     `SIMPLE_DUNGEON` loot.
3. To link two dimensions with a portal: build and light a normal
   Nether portal (obsidian + flint and steel) anywhere in one of your
   Nexus worlds, stand in it, and run
   `/nexusdim portal link <otherWorldName>` (optionally add `destX destY
   destZ` to land at exact coordinates instead of that world's spawn).
   Walk through and you'll arrive there instead of vanilla's own
   Nether/End pairing. `/nexusdim portal list` shows every registered
   link; `/nexusdim portal unlink` (stand near the portal) removes just
   the registration, not the physical blocks. Linking is one-directional
   by default — build and link a second portal on the other side for a
   return trip, or if there's already a lit portal waiting near the
   destination, add `both` to the end of the `link` command
   (`/nexusdim portal link <otherWorldName> both`, or
   `/nexusdim portal link <otherWorldName> destX destY destZ both`) to
   have it linked back automatically in the same step.
4. To see mob reskinning: `moon` naturally spawns Endermen (the only
   hostile `minecraft:the_end` produces), reskinned as faster, glowing
   "Lunar Wraiths." `iron_giant_world` naturally spawns Magma Cubes and
   Ghasts from its `minecraft:basalt_deltas` biome, reskinned as tougher
   "Molten Behemoths"/"Ember Wardens" with named tags always visible.
5. To see seasons: stay in `ice_moon` for a while. It cycles a ~10-minute
   "Calm" stage (light snow, forced-clear weather) into a ~5-minute
   "Blizzard" stage (heavier snow particles, stronger wind push, and real
   vanilla weather allowed to roll in) on a loop.
6. `/nexusdim create my_spires crystal_spires` — Tier 1, instant. Thin
   crystalline rock needles instead of horizontal islands, dark obsidian
   veins breaking up the end stone, rare glowstone pockets to find while
   exploring inside them, and giant amethyst-accented "Amethyst Sentinel"
   trees rooted right on the spire tops — the "wilder terrain" + "beautiful
   trees" showcase preset.
7. Write your own preset by copying one of the examples and editing it —
   that's the whole point of the config system. Copy
   `blueprints/ruin_small.yml` to author your own structures the same way.
   `/nexusdim reload` picks up edits to existing presets and blueprints
   without a restart (Tier 2 preset edits still need a restart to actually
   change the live dimension, same as creating a new one).

## Known limitations (see DESIGN.md section 1 for the full explanation)

- **Gravity** is real vanilla `JUMP_BOOST`/`SLOW_FALLING`/jump-cancellation,
  not a physics-engine change — there's no legitimate server-side hook to
  change actual fall acceleration in vanilla-protocol Minecraft. It reads
  as floaty/heavy convincingly; it won't affect projectiles, item drops,
  or non-player entities, only players.
- **Tier 2 terrain is vanilla flat generation**, not our noise terrain —
  only Tier 1 worlds get the custom crater/ocean/ridged terrain shaping,
  because Tier 1 is the only case where a Bukkit plugin is actually
  handed the chunk generator.
- Tier 2 dimensions need **one restart** after `/nexusdim create` writes
  the datapack — that's when vanilla discovers the new datapack, enables
  it, and registers the new dimension type/dimension; the dimension is
  live from that restart onward.
- **`terrain.mode: density3d` liquids (`terrain.density3d.liquids`,
  opt-in) are a flat Y threshold, not a connected-component flood fill** —
  reads as a genuine single ocean when there's nothing but open air below
  the threshold (see `floating_isles.yml`), but a stray air pocket
  anywhere else at the same Y (inside a spire, say) floods independently
  too, with no awareness it isn't "the same" body of water. See DESIGN.md
  section 4c for the honest version of this tradeoff. `density3d` mode is
  also Tier 1 only, same reasoning as the rest of terrain shaping, and
  more expensive per chunk than heightmap mode (density evaluated per
  block, not per column) — keep `noise.octaves` modest (3-4) for it.
- **Blueprints are plain block lists, not schematics** — no in-game
  structure editor. `structures.randomRotation`/`randomMirror` (opt-in)
  give up to 8 orientations of the same blueprint, but only transform
  block *positions* — a directional block (stairs, logs, chests, ...)
  still faces however it was originally authored after being moved to a
  rotated position. Safe for non-directional blueprints like the bundled
  `ruin_small`; not a substitute for real per-block re-orientation. See
  DESIGN.md section 9.
- **Portal links are one-directional by default** — linking A → B does
  not automatically create B → A unless you add `both` to the `link`
  command, and even then it's best-effort (it only finds and links an
  *existing* lit portal near the destination; it never places one for
  you). See DESIGN.md section 10.
- **`creatures.spawnMultiplier` can only thin natural spawns, not boost
  them** — there's no legitimate Bukkit hook to make vanilla attempt
  natural spawns more often than it already decided to. Values above 1.0
  are clamped to 1.0 with a warning. See DESIGN.md section 11.
- **No new mob species and no AI/behavior edits yet** — `creatures.mobs`
  reskins (attributes/equipment/name/glow) real vanilla mobs; it can't add
  a mob type that doesn't exist or change what a mob actually does. Genuine
  AI edits are possible via Paper's Mob Goal API and are on the roadmap,
  not attempted here.
- **Seasons only affect particles/spawn-thinning/weather, never placed
  blocks** — no temporary snow/ice overlay on terrain yet, and season
  phase resets to the start of the cycle on every restart (not persisted).
  See DESIGN.md section 12.
- **`caves.mode: cellular` and `terrain.density3d.shape: spires` are both
  heightmap/density3d-specific** (cellular caves need `terrain.caves`,
  a heightmap-only concept; spires need `terrain.density3d`, density3d-only)
  and both, like every other density/threshold value in this project, were
  tuned against one specific set of noise/band parameters — copy a
  preset's exact `noise`/`density3d` block as a starting point rather than
  mixing tuned numbers from two different presets. See DESIGN.md section 4c.
- **`palette.variants`/`palette.glowDeposits` thresholds are raw noise
  values, not literal coverage percentages** — fBm output clusters near 0
  rather than being uniform, so "threshold 0.2" isn't "20% coverage." See
  the sampled reference table in DESIGN.md section 4c before tuning.
- **Tree branches/buttress roots/canopy wobble add a modest amount of
  placement cost per tree** — noticeable only if `trees.rarityPerChunk` is
  set very high (trees are already meant to be rare per DESIGN.md section
  0's infinite-generation framing, so this hasn't been a problem in any of
  the bundled presets).
- **`decorations:` and `flavor.generateDecorations` are unrelated despite
  the shared word** — `decorations:` is this project's own ground-clutter
  system (section 5c); `flavor.generateDecorations` toggles *vanilla's*
  built-in decoration/feature generation pass on a Tier 1 world and
  predates this feature entirely. Toggling one does nothing to the other.
  `decorations.perChunkAttempts` is also a per-chunk cost multiplier the
  same way `trees.rarityPerChunk` is — the bundled presets keep it small
  (2-4) for exactly that reason.
