# King Crimson — Time Erase Visual Upgrade

A personal Fabric 1.20.1 build recipe for Roundabout. It keeps the real Roundabout King Crimson ability and enhances its Time Erase visual effects.

## What this build changes
- Keeps Roundabout's original King Crimson / Time Erase ability, controls, skybox, red entity projections, and ending sound.
- Preserves the existing King Crimson aura hook: pulsing red rings, rising filaments, and the red particle aura around the summoned stand.
- At activation, gathers nearby exposed solid blocks client-side. Blocks stay whole until the fracture wave reaches them.
- Each affected cube is split into six real tetrahedral 3D wedges. The exterior faces use the block atlas texture; newly exposed inner faces are tinted bright red/purple.
- The wedges open by a small 0.03–0.08 block gap over 8 ticks, with only a subtle fixed 2–5 degree tilt. They do not continuously spin.
- After the crack phase, the wedges drift outward/upward for 10 ticks (0.5 seconds) and dissolve over 6 ticks.
- The fracture starts propagate outward from the player with a distance-based delay. No extra line or travelling stripe is drawn over the blocks.
- The original Time Erase activation sound is replaced with the user's OGG. The original ending sound is left untouched.
- The world is not modified: block fractures and fragments are client-side render effects only. When Time Erase ends, the original terrain is restored immediately.

## Build
GitHub Actions checks out the pinned Roundabout source, applies the patch, replaces the activation OGG, and builds the Fabric jar with Java 17. The jar is published as a workflow artifact.

Install the generated jar alongside the normal Fabric 1.20.1 dependencies required by Roundabout. This output is a modified Roundabout jar, not a standalone addon; do not install both the modified jar and the original Roundabout jar together.

## Credits and license
Roundabout source: https://github.com/Hydraheads/RoundaboutMod  
Upstream revision: `16ae85aa2338269192923418ad42930215a74f0c`. The original license and attribution are retained in the built jar.

This is an unofficial personal modification and is not affiliated with the Roundabout authors or the JoJo's Bizarre Adventure rights holders.
