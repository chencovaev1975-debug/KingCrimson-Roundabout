# Cosmic fracture patch — verification notes

## Patched files (by `patches/apply_patch.py`)

1. `common/src/main/java/net/hydra/jojomod/client/ClientUtil.java`
   - the `ClientEffectUtil.spawnTerrainFragment(...)` call inside the King Crimson Time Erase
     branch is removed and `ClientEffectUtil.terrainFragments.clear()` runs instead, so no
     levitating blocks remain during Time Erase.
2. `common/src/main/java/net/hydra/jojomod/mixin/ZLevelRenderer.java`
   - world chunks stop rendering once the native Time Erase fade-in finished
     (`ClientUtil.isUsingTimeErase && ClientUtil.TimeErase >= ClientUtil.fadeTime`);
   - `VCosmicFragmentEffect.renderWorld(...)` is called right after the existing
     `rdbt$renderTerrainFragments(...)` call in the sky pass, before `buffer.endBatch()`.
3. `common/src/main/java/net/hydra/jojomod/mixin/HudRendering.java`
   - `VCosmicFragmentEffect.renderScreenOverlay(...)` is called at the head of
     `roundabout$renderHotbarMixin` for the pink sweep.
4. New client-only class `common/src/main/java/net/hydra/jojomod/client/VCosmicFragmentEffect.java`
   copied from `patches/VCosmicFragmentEffect.java`.

Every string replacement asserts exactly one match and aborts the build otherwise, so an
upstream change cannot silently skip a hook.

## Effect timeline

- Key press (ability-four key, V by default) is detected from `KeyMapping#isDown()` on a rising
  edge, inside the render hook — no per-tick state and no keybind registration are needed.
- 1.10–1.45 s: 32 stacked translucent slices form one very wide pink band moving bottom to top.
- 2.00 s: snapshot (≤520 exposed blocks, 15-block radius, ±10 Y, reservoir sampled) is rendered
  as 2–3 large fragments per block.
- 2.00–2.60 s: fragments separate slightly and hover with a small sine bob.
- 2.60 s: short cross-fade to smaller splinters (1–2 per large fragment) plus a three-axis
  high-frequency shake and outward scatter.
- 4.00–4.90 s: splinters scale to zero and drift upward, then the state is cleared.
- The effect is dropped immediately if the level changes, or if it was started during Time
  Erase and the ability ends.

## Verification performed

- `patches/apply_patch.py` was run locally against the pinned upstream sources
  (`ClientUtil.java`, `ZLevelRenderer.java`, `HudRendering.java` from
  `Hydraheads/RoundaboutMod@16ae85aa`) — all four patch points matched exactly once.
- `patches/VCosmicFragmentEffect.java` passed a `javac` parse check (only expected
  "package does not exist" / "cannot find symbol" errors for Minecraft classes, no syntax
  errors).
- Every Minecraft API used by the new class (`renderSingleBlock`, `RenderShape.MODEL`,
  `canOcclude`, `getFluidState`, `getMinBuildHeight`/`getMaxBuildHeight`, `GuiGraphics#fill`,
  `Util.getMillis`, `Mth.sin/clamp/floor`, `Camera#getPosition`, `LightTexture.FULL_BRIGHT`,
  `OverlayTexture.NO_OVERLAY`) is already used elsewhere in the pinned Roundabout sources, so
  the names and signatures match this exact revision.

## Not verified here

- A full Gradle build and an in-game look were not run locally. The GitHub Actions workflow
  compiles the jar; treat the first green run as the compile check and tune amplitudes in
  `VCosmicFragmentEffect` afterwards if the look needs adjusting.

## Tuning knobs

All in `VCosmicFragmentEffect.java`:

- `STRIPE_START`, `STRIPE_END` — pink sweep timing.
- `SHATTER_TIME`, `SHAKE_TIME`, `DISSOLVE_TIME`, `END_TIME` — fracture beats.
- `RADIUS`, `VERTICAL_DOWN`, `VERTICAL_UP`, `MAX_BLOCKS` — snapshot size and therefore cost.
- `largeCount`, `smallPerLarge` in `buildFragments` — fragment density.
- `shakeAmp` in `renderShattered` — how violent the shake is.
