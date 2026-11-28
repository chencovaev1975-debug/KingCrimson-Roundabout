# King Crimson — Cosmic Fracture Build

Personal modified Roundabout build for Minecraft Java 1.20.1 / Fabric.

## Time Erase (V) — what changed

- Roundabout's **levitating blocks are gone**: the stock `TerrainFragments` scraps are no longer
  spawned during Time Erase, and any scrap that already exists is cleared on the client.
- After the native Time Erase fade-in finishes, ordinary world chunks stop rendering, so the
  screen is left with Roundabout's own dark cosmic skybox and its stars.
- The native fade-in, fade-out and skybox are otherwise untouched. No crack overlay, no camera
  shake, no extra sound.

## New client-side effect on V

The effect is purely cosmetic and client-side (no packets, no block edits, no collision or
ability changes). The timeline uses real client time, so it is identical at any frame rate.

| Time | What happens |
| --- | --- |
| 1.10 s | A very wide, bright-pink band sweeps bottom to top across the whole screen over 0.20 s. |
| 2.00 s | Nearby blocks break into a small number of large debris chunks of very different shapes and sizes — a few huge multi-block pieces plus some small ones. They keep the block's own texture, open only a hairline gap and stay exactly where they were, with a barely visible hover. |
| 2.60 s | Each chunk cracks in two, and the whole view shakes gently. |
| 4.00 s | All small splinters smoothly shrink and drift away, leaving only the dark starry cosmos. |
| 4.90 s | Effect state is cleared. |

Implementation notes:

- On the key press the client snapshots **every** exposed block in a 15-block radius
  (±15 blocks vertically) — no sampling, nothing is dropped, so no holes appear. Block states
  are only read, never written. The only cap is a 6000-block safety net far above a normal
  snapshot.
- Debris is built from **clusters of neighbouring blocks of the same type** (up to 44 blocks), so
  one chunk can be several blocks across instead of a single scaled-down cube. A cluster becomes
  1–3 chunks depending on its size, and a 1-block cluster stays small debris — that is where the
  mix of huge and small fragments comes from.
- Every chunk is a cut, sheared and corner-folded piece of that volume: corners are jittered,
  faces are sheared so none stay parallel to the block faces, and corners are folded onto their
  neighbours so quads collapse into triangles. The result is an angular debris wedge, not a cube,
  textured with that block's own sprite (tiled per block cell across the chunk surface) with
  vanilla-like face shading.
- Chunks never fly away when they break: they only open a hairline gap (10 % of their offset from
  the cluster centre), rotate by ±3° over 0.6 s and hover by 0.012 blocks.
- The shake of the 2.60 s beat is a **camera-style shake of the whole view** (pose offset applied
  at the head of the level render and undone at the tail); the fragments themselves no longer
  jitter.
- Before 2.00 s the snapshotted blocks stand in for the hidden chunks, so the world does not
  visibly pop when Time Erase hides the terrain.
- The whole effect is bounded and frame-rate driven; nothing is stored per tick and nothing
  leaks between worlds (the state is dropped on level change).

## Audio

Only the Time Erase activation sound is replaced with the user's recording, transcoded to
Ogg Vorbis for Minecraft 1.20.1. The original ending sound is unchanged.

## Build and install

GitHub Actions checks out Roundabout at pinned revision
`16ae85aa2338269192923418ad42930215a74f0c`, applies `patches/apply_patch.py`, replaces the
activation sound and builds the Fabric jar with Java 17.

This output is a modified Roundabout jar, not a standalone addon. Install it with the
dependencies normally required by Roundabout, and remove the original Roundabout jar from
`mods` so there is only one copy.

## Credits

Upstream source: https://github.com/Hydraheads/RoundaboutMod
The upstream license and attribution are retained in the built jar. This is an unofficial
personal modification and is not affiliated with the Roundabout authors or JoJo's Bizarre
Adventure rights holders.
