# King Crimson — Time Erase Visual Upgrade

A personal Fabric 1.20.1 build recipe for Roundabout. It keeps the real Roundabout King Crimson ability and enhances its existing Time Erase terrain effect.

## What this build changes
- Keeps Roundabout's actual King Crimson / Time Erase activation and deactivation.
- Reuses Roundabout's own Time Erase skybox and its original `time_erase_full` / `time_erase_end` sound events. No duplicate keybind or parallel ability is added.
- Replaces the random distant full-block debris with nearby staged cracks and four large, rotating, block-textured debris chunks per affected block. The chunks rise slowly and shrink away instead of remaining as tiny chips.
- Leaves the world untouched: the fragments are client-side render effects only.
- Retains Roundabout's own red entity projections and existing sky fade.

## Build
GitHub Actions clones the upstream Roundabout source at the pinned revision, applies the small source patch in `patches/`, then builds the Fabric jar with Java 17. The jar is published as a workflow artifact.

Install the generated jar alongside the normal Fabric 1.20.1 dependencies required by Roundabout. This output is a modified Roundabout jar, not a standalone addon; do not install both the modified jar and the original Roundabout jar together.

## Credits and license
Roundabout source: https://github.com/Hydraheads/RoundaboutMod  
Roundabout's repository license explicitly permits modifications and distribution with attribution. The build uses the upstream source at revision `16ae85aa2338269192923418ad42930215a74f0c`; its original license and attribution are retained in the built jar.

This is an unofficial personal modification and is not affiliated with the Roundabout authors or the JoJo's Bizarre Adventure rights holders.
