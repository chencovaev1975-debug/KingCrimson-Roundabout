# Time Erase fracture patch — verification notes

## Implemented timeline

- Ticks 0–5: capture six concentric 10-block rings, up to 4,200 exposed solid blocks in total.
- Ticks 0–44: dark grey face cracks appear and spread outward from the player.
- Ticks 42–48: irregular stone prisms grow outward from exposed block faces while the blocks are still rendered.
- Tick 48 (2.40 s): the temporary block layer stops rendering, the chips detach, and a vertical-only camera shake begins.
- Tick 60 (3.00 s): the chips have faded and the effect data is cleared.

The visual layer is client-side and does not write block states, send world-modification packets, alter collision, or change entity/ability behavior. It adds no sound. The native skybox and native Time Erase fade/return path are left in place.

## Verification actually performed

- The patch script was applied successfully to a clean reconstruction of the two upstream source files from the supplied build's `sources.jar`, after reversing the existing addon patch. All new hooks and the new source file were verified in the result.
- `TimeEraseFractureEffect.java` passed a Java 17 syntax/type-shape check against lightweight Minecraft API stubs.
- `apply_patch.py` passed Python bytecode compilation.

A full Gradle build against the pinned Roundabout checkout and an in-game test have **not** been run for this update. Run the repository's GitHub Actions workflow before treating this update as a verified Minecraft build.

## Known visual tuning limit

The crack lines currently use a neutral charcoal-grey tone. The crack-reference PNG was not included in the two uploaded ZIP files, so exact pixel/color matching to that image has not been verified.

## Existing fade timing

No independent 0.5-second post-effect delay was found in the inspected Time Erase logic. The existing 14-tick native fade-down controls both the skybox fade and the return of ordinary chunk rendering; it is intentionally unchanged so the world-return behavior is not disturbed.
