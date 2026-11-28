# King Crimson — Clean Time Erase (native transition + hidden world)

Personal modified Roundabout build for Minecraft Java 1.20.1 / Fabric.

## What this build does

- On activation (V) Roundabout's **original** Time Erase transition plays completely untouched
  (dark-magenta fog, floating terrain scraps, native skybox fade-in).
- When the native fade-in finishes, world chunks stop rendering, so only Roundabout's
  native cosmic skybox remains.
- When the ability ends, chunks are rendered again and the native fade-out plays.

No cracks, no shards, no camera shake, no extra overlays. No gameplay logic is changed.

## Audio

Only the Time Erase activation sound file is replaced with the user's recording
(transcoded to Ogg Vorbis). Unchanged from the previous build.

## Build

GitHub Actions checks out Roundabout at pinned revision
`16ae85aa2338269192923418ad42930215a74f0c`, applies `patches/apply_patch.py`,
and builds the Fabric jar with Java 17. Remove the original Roundabout jar from `mods`.

Upstream: https://github.com/Hydraheads/RoundaboutMod (license and attribution retained).
Unofficial personal modification.
