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
| 1.10 s | A very wide, bright-pink band sweeps bottom to top across the whole screen over 0.35 s. |
| 2.00 s | Surface blocks and trees within 15 blocks break into large and medium chaotic 3D fragments that keep the original block textures. The pieces pull apart slightly in place and begin a barely visible up/down levitation. |
| 2.60 s | The same pieces start shaking hard and split into much smaller splinters. |
| 4.00 s | All small splinters smoothly shrink and drift away, leaving only the dark starry cosmos. |
| 4.90 s | Effect state is cleared. |

Implementation notes:

- On the key press the client snapshots up to 520 exposed blocks in a 15-block radius,
  ±10 blocks vertically, using deterministic reservoir sampling so repeated presses look the
  same and the scan stays cheap. Block states are only read, never written.
- Every fragment is rendered as a scaled, textured copy of its own block model
  (`BlockRenderDispatcher#renderSingleBlock`), so grass, dirt, sand, logs and leaves all keep
  their real textures and lightmap.
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
