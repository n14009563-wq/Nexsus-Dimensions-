# NexusDimensions changelog

## v0.1.1 -- mob-spawn thinning + real-build compile fixes

Two rounds of changes, both triggered by the same underlying report: a
player was experiencing severe lag specifically while inside the bundled
`ocean_planet` Tier 1 dimension, and it cleared up immediately on leaving
that world. Root cause: `ocean_planet.yml` is a near-total-ocean world by
design (see its own header comment), which means vanilla's untouched
natural-spawn logic filled essentially every loaded chunk near the player
with max water-mob density (squid/cod/salmon/tropical fish/dolphins, plus
Drowned) -- a much higher sustained entity count, and per-player
entity-tracking/packet load, than any normal Minecraft world where land
breaks up the ocean.

**Round 1 -- decoupling spawn thinning from mob reskinning
(`MobCustomizationListener`):** the plugin already had a lever for exactly
this (`creatures.spawnMultiplier`, thins -- never boosts -- natural
spawns), but `onSpawn()` gated the entire `creatures` block, thinning
included, behind `creatures.enabled`, a flag that's really about the
*reskinning* feature (attributes/equipment/name/glow) below it. A preset
that just wants fewer or zero natural mobs shouldn't have to also opt into
reskinning to get there. Fixed by moving the `spawnMultiplier` check ahead
of, and independent of, the `!preset.creatures.enabled` early-return --
reskinning still requires `enabled: true`, thinning no longer does.
`ocean_planet.yml` now ships with:

```yaml
creatures:
  spawnMultiplier: 0.0
```

which cancels essentially all `NATURAL`-reason spawns in that dimension
(and can be dialed to any value in between if a preset wants fewer mobs
rather than none).

**Round 2 -- real compile errors against a real `mvn package` build.**
This project was originally written and verified in a sandbox with no
outbound access to Maven Central or PaperMC's repository (see README's
Building section) -- source compiled cleanly with `javac` alone (no Paper
API on the classpath), and the POM was confirmed mechanically sound as far
as dependency resolution got before hitting the sandbox's network
restriction. The README's own "if anything fails to compile" list flagged
several specific spots as uncertain rather than papering over them. Once
actually built against the real `paper-api 1.21.4-R0.1-SNAPSHOT` jar, two
of those flagged spots turned out to be genuinely wrong, not just
uncertain:

- `LimitedRegion.isInRegionBounds(int, int, int)` -- doesn't exist. The
  real method is `isInRegion(int, int, int)`. Fixed in `GiantTreePopulator`,
  `StructurePopulator`, and `DecorationPopulator` (three call sites, one
  per populator, all doing the same "is this write still inside the
  chunk-populate region?" check).
- `Attribute.GENERIC_MAX_HEALTH` / `GENERIC_MOVEMENT_SPEED` /
  `GENERIC_ATTACK_DAMAGE` / `GENERIC_SCALE` -- none of these exist on
  1.21.4. Minecraft 1.21 dropped the `generic.` namespace prefix from
  attribute keys (`minecraft:generic.max_health` ->
  `minecraft:max_health`, etc.) and Paper's `Attribute` constants were
  renamed to match: `MAX_HEALTH` / `MOVEMENT_SPEED` / `ATTACK_DAMAGE` /
  `SCALE`. The README had only flagged `GENERIC_SCALE` as worth
  double-checking (newest attribute, added 1.20.5) and called the other
  three "long-stable, not in question" -- that assumption was wrong; all
  four needed the same fix. All four call sites are in
  `MobCustomizationListener#applyProfile`/`applyAttribute`.

Both fixes verified against the actual error output from a real
`mvn clean package` run (Paper API 1.21.4-R0.1-20250925.065901-231,
compiled with `javac [debug release 21]`) -- the exact four `Attribute`
symbols and the exact `LimitedRegion` symbol reported as "cannot find
symbol" are the exact ones changed here, nothing more, nothing guessed.
`Attribute` itself also changed from a plain enum to a
`Registry`-backed interface in this API version, but that's transparent
to this code -- the renamed constants are still plain public static
fields, referenced exactly the same way (`Attribute.MAX_HEALTH`, not
`Registry.ATTRIBUTE.get(...)`).

README.md and DESIGN.md updated to strike the now-resolved uncertain-API
entries (kept, struck through, as a record of what changed) rather than
silently deleting them.

## v0.1.0 -- first release

Initial scaffold: config-driven custom dimensions for Paper 1.21.x with
fully custom noise terrain (heightmap and density3d modes), giant flora,
ground decorations, hand-authored blueprint structures, per-world gravity/
weather/particles/seasons, mob reskinning, and portals between dimensions.
Two-tier architecture (instant Tier 1 worlds with a fully custom
`ChunkGenerator`, vs. restart-requiring Tier 2 datapack dimensions for
custom world height/sky) -- see DESIGN.md section 1 for the full honest
capability picture. Verified in a sandbox with no outbound network access
via `javac` against the source alone (confirming every error was an
expected missing-Paper-API symbol) and a `mvn compile` run that got as far
as this project's own POM/dependencies resolving correctly before hitting
the sandbox's network restriction fetching build plugins.
