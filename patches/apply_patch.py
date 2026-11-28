from pathlib import Path

root = Path(".")
client = root / "common/src/main/java/net/hydra/jojomod/client/ClientUtil.java"
renderer = root / "common/src/main/java/net/hydra/jojomod/mixin/ZLevelRenderer.java"
hud = root / "common/src/main/java/net/hydra/jojomod/mixin/HudRendering.java"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


# ---------------------------------------------------------------------------
# 1. Remove the floating terrain scraps from Time Erase.
#
# Roundabout spawns TerrainFragments while King Crimson's Time Erase runs; those are
# the "levitating blocks" that were drifting in the void. They are dropped here, and
# any already spawned scrap is cleared every client tick, so Time Erase stays clean.
# ---------------------------------------------------------------------------
replace_once(
    client,
    """                if (PKC.isUsingTimeErase()){
                    if (clientTicker % 2 == 0) {
                        ClientEffectUtil.spawnTerrainFragment(player);
                    }
                    isUsingTimeErase = true;""",
    """                if (PKC.isUsingTimeErase()){
                    ClientEffectUtil.terrainFragments.clear();
                    isUsingTimeErase = true;""",
    "remove levitating terrain fragments from Time Erase",
)

# ---------------------------------------------------------------------------
# 2. Once the native Time Erase fade-in has finished, stop drawing world chunks so
# the native cosmic skybox is the only background that is left.
# ---------------------------------------------------------------------------
replace_once(
    renderer,
    """        if (ClientUtil.isUnderRTZ() && ClientUtil.rtzTicks >= 4 && ClientUtil.rtzTicks < ClientUtil.RTZ_DURATION - 4) {
            ci.cancel();
        }
    }""",
    """        if (ClientUtil.isUnderRTZ() && ClientUtil.rtzTicks >= 4 && ClientUtil.rtzTicks < ClientUtil.RTZ_DURATION - 4) {
            ci.cancel();
        }
        if (ClientUtil.isUsingTimeErase && ClientUtil.TimeErase >= ClientUtil.fadeTime) {
            ci.cancel();
        }
    }""",
    "hide world chunks after the native Time Erase transition",
)

# ---------------------------------------------------------------------------
# 3. Hook the new V-key cosmic fracture effect into the same sky pass that already
# renders Roundabout's own sky geometry, and pull in its class.
# ---------------------------------------------------------------------------
if "import net.hydra.jojomod.client.VCosmicFragmentEffect;" not in renderer.read_text(encoding="utf-8"):
    replace_once(
        renderer,
        "import net.hydra.jojomod.client.ClientUtil;",
        "import net.hydra.jojomod.client.ClientUtil;\nimport net.hydra.jojomod.client.VCosmicFragmentEffect;",
        "import the V-key effect into the level renderer",
    )

replace_once(
    renderer,
    """        rdbt$renderTerrainFragments(
                $$0,
                buffer,
                $$3,
                $$2
        );
""",
    """        rdbt$renderTerrainFragments(
                $$0,
                buffer,
                $$3,
                $$2
        );
        VCosmicFragmentEffect.renderWorld($$0, buffer, $$3, $$2);
""",
    "render the V-key cosmic fracture effect",
)

# ---------------------------------------------------------------------------
# 4. Draw the wide bright-pink sweep as a HUD overlay.
# ---------------------------------------------------------------------------
if "import net.hydra.jojomod.client.VCosmicFragmentEffect;" not in hud.read_text(encoding="utf-8"):
    replace_once(
        hud,
        "import net.hydra.jojomod.client.StandIcons;",
        "import net.hydra.jojomod.client.StandIcons;\nimport net.hydra.jojomod.client.VCosmicFragmentEffect;",
        "import the V-key effect into the HUD renderer",
    )

replace_once(
    hud,
    """    private void roundabout$renderHotbarMixin(float $$0, GuiGraphics $$1, CallbackInfo info) {
""",
    """    private void roundabout$renderHotbarMixin(float $$0, GuiGraphics $$1, CallbackInfo info) {

        VCosmicFragmentEffect.renderScreenOverlay($$1);
""",
    "render the pink sweep overlay",
)

# ---------------------------------------------------------------------------
# 5. Copy the client-only effect implementation into the mod sources.
# ---------------------------------------------------------------------------
effect_source = Path(__file__).with_name("VCosmicFragmentEffect.java")
if not effect_source.exists():
    raise SystemExit("Missing addon source: patches/VCosmicFragmentEffect.java")
effect_target = client.parent / "VCosmicFragmentEffect.java"
effect_target.write_text(effect_source.read_text(encoding="utf-8"), encoding="utf-8")

print("Applied: Time Erase keeps only the native cosmic void (no levitating blocks),")
print("and pressing V plays the client-side pink sweep + block fracture effect.")
