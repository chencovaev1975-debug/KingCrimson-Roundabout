# King Crimson — Clean Time Erase Void

Personal modified Roundabout build for Minecraft Java 1.20.1 / Fabric.

## Time Erase visuals
- During Time Erase, ordinary world chunk geometry is hidden.
- Roundabout's original six-face cosmic skybox remains visible and fades in/out using the mod's native Time Erase timing.
- No aura, no crack overlays, no block shards, no floating debris, and no extra visual lines.
- No world blocks are modified; the terrain reappears when the native Time Erase fade finishes.
- Original Roundabout entity projections and ability behavior are retained.

## Audio
- Replaces only the Time Erase activation sound file with the user's recording.
- The recording is transcoded to Ogg Vorbis, which Minecraft 1.20.1 can decode.
- The original Time Erase ending sound is unchanged.

## Build and install
GitHub Actions checks out Roundabout at pinned revision `16ae85aa2338269192923418ad42930215a74f0c`, applies this patch, and builds the Fabric jar with Java 17.

This output is a modified Roundabout jar, not a standalone addon. Install it with the dependencies normally required by Roundabout, and remove the original Roundabout jar from `mods` so there is only one copy.

## Credits
Upstream source: https://github.com/Hydraheads/RoundaboutMod  
The upstream license and attribution are retained in the built jar. This is an unofficial personal modification and is not affiliated with the Roundabout authors or JoJo's Bizarre Adventure rights holders.
