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
     `rdbt$renderTerrainFragments(...)` call in the sky pass, before `buffer.endBatch()`;
   - `VCosmicFragmentEffect.applyScreenShake(...)` at the head of `renderLevel` and
     `VCosmicFragmentEffect.clearScreenShake(...)` at its tail (pose offset in/out).
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
- 1.10–1.30 s: 32 stacked translucent slices form one very wide pink band moving bottom to top.
- 2.00 s: snapshot of **all** exposed blocks (15-block radius, ±15 Y, no sampling, 6000-block
  safety net) is grouped into clusters of neighbouring blocks with the same state (≤44 blocks)
  and each cluster is cut into 1–3 chunks (1 for ≤12 cells, 2 for ≤26, otherwise 3), so debris
  sizes vary from single blocks to multi-block slabs.
- 2.00–2.60 s: chunks open a hairline gap (10 % of their offset from the cluster centre),
  rotate ±3° and hover by 0.012 blocks — they stay in place and never drift away.
- 2.60 s: short cross-fade to two halves per chunk (a single-block chunk is cracked through its
  own volume), plus a gentle view shake (pose offset 0.038 blocks, decaying after 3.55 s).
- 4.00–4.90 s: splinters scale down, fade out and drift upward, then the state is cleared.
- Geometry: chunk box = bounds of its block cells; per-corner jitter (7.5 % of the smallest
  extent, capped at 0.26 blocks), shear of 11 % of that extent (faces stop being axis aligned)
  and 0–2 corner folds that collapse quads into triangular faces. Six faces per chunk,
  subdivided once per block cell so the sprite keeps tiling across a multi-block chunk, textured
  from the block's own `getParticleIcon()` sprite with vanilla-like face shading,
  `RenderType.entityTranslucent(atlas)` with alpha for the dissolve, `LightTexture.FULL_BRIGHT`.
- The effect is dropped immediately if the level changes, or if it was started during Time
  Erase and the ability ends.

## Verification performed

- `patches/apply_patch.py` was run locally against the pinned upstream sources
  (`ClientUtil.java`, `ZLevelRenderer.java`, `HudRendering.java` from
  `Hydraheads/RoundaboutMod@16ae85aa`) — all four patch points matched exactly once.
- `patches/VCosmicFragmentEffect.java` passed a `javac` parse check (only expected
  "package does not exist" / "cannot find symbol" errors for Minecraft classes, no syntax
  errors).
- All six patch points matched exactly once on the pinned sources.
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
