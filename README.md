# King Crimson — Time Erase Fracture Build

Personal modified Roundabout build for Minecraft Java 1.20.1 / Fabric.

## Time Erase visuals

- Roundabout's native six-face cosmic skybox and its activation fade are retained.
- Ordinary chunk geometry remains hidden during Time Erase; no world chunk or block is changed on the client or server.
- For the first 3 seconds, the local client snapshots up to 4,200 exposed solid blocks in a 60-block horizontal radius and renders them as a temporary visual layer. The radius is scanned in six 10-block rings over the first six client ticks to avoid one large activation-time scan.
- Dark stone-grey cracks spread outward from the player across the block faces. At 2.40 seconds, the camera receives a short vertical-only shake (no roll, yaw, pitch, FOV pulse, or motion blur).
- In the final part of the fracture pass, 2–4 irregular, volumetric grey stone chips form at exposed faces of each selected block. They separate without spinning, drift slightly upward/outward, and fade away by 3.00 seconds.
- After the first 3 seconds, only the normal Time Erase void and native skybox remain for the rest of the ability.
- The effect is client-side and local to the player using King Crimson. It does not alter collision, mobs, block states, the stand model, or ability logic.
- No additional sound effects are added. The existing ending sound is unchanged.

The effect uses a bounded sample of up to 4,200 exposed blocks to keep rendering cost bounded. It reads client-side block states only; it does not write block states or send world-modification packets. The scan is limited to a vertical band from 18 blocks below to 14 blocks above the player.

## Audio

The build replaces only the Time Erase activation sound with the user's recording, transcoded to Ogg Vorbis for Minecraft 1.20.1. The original Time Erase ending sound is unchanged.

## Build and install

GitHub Actions checks out Roundabout at pinned revision `16ae85aa2338269192923418ad42930215a74f0c`, applies this patch, and builds the Fabric jar with Java 17.

This output is a modified Roundabout jar, not a standalone addon. Install it with the dependencies normally required by Roundabout, and remove the original Roundabout jar from `mods` so there is only one copy.

## Credits

Upstream source: https://github.com/Hydraheads/RoundaboutMod  
The upstream license and attribution are retained in the built jar. This is an unofficial personal modification and is not affiliated with the Roundabout authors or JoJo's Bizarre Adventure rights holders.
