from pathlib import Path

root = Path(".")
client = root / "common/src/main/java/net/hydra/jojomod/client/ClientUtil.java"
renderer = root / "common/src/main/java/net/hydra/jojomod/mixin/ZLevelRenderer.java"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


# Disable Roundabout's stock floating terrain fragments. The custom fracture pass
# below renders selected world blocks and its own non-rotating stone chips locally.
replace_once(
    client,
    """                if (PKC.isUsingTimeErase()){
                    if (clientTicker % 2 == 0) {
                        ClientEffectUtil.spawnTerrainFragment(player);
                    }
                    isUsingTimeErase = true;""",
    """                if (PKC.isUsingTimeErase()){
                    if (!isUsingTimeErase) {
                        ClientEffectUtil.terrainFragments.clear();
                    }
                    isUsingTimeErase = true;""",
    "disable stock Time Erase terrain fragments"
)

# Keep ordinary chunks hidden; only the bounded client-side fracture snapshot is re-rendered.
replace_once(
    renderer,
    """        if (ClientUtil.isUnderRTZ() && ClientUtil.rtzTicks >= 4 && ClientUtil.rtzTicks < ClientUtil.RTZ_DURATION - 4) {
            ci.cancel();
        }
    }""",
    """        if (ClientUtil.isUnderRTZ() && ClientUtil.rtzTicks >= 4 && ClientUtil.rtzTicks < ClientUtil.RTZ_DURATION - 4) {
            ci.cancel();
        }
        if (ClientUtil.renderTimeErase()) {
            ci.cancel();
        }
    }""",
    "hide normal world chunks during Time Erase"
)

# Let the native Roundabout six-face skybox remain visible and keep clouds out of it.
replace_once(
    renderer,
    "if (ClientUtil.isUsingTimeErase || ClientUtil.isUnderRTZ()) {",
    "if (ClientUtil.renderTimeErase() || ClientUtil.isUnderRTZ()) {",
    "hide clouds during the Time Erase skybox fade"
)


# Add the local-only fracture effect. The class is deliberately client-only and only
# snapshots block states: it never edits blocks, entities, collision, or server state.
fracture_class = root / "common/src/main/java/net/hydra/jojomod/client/TimeEraseFractureEffect.java"
fracture_class.parent.mkdir(parents=True, exist_ok=True)
fracture_source = Path(__file__).with_name("TimeEraseFractureEffect.java")
if not fracture_source.exists():
    raise SystemExit("Missing addon source: patches/TimeEraseFractureEffect.java")
fracture_class.write_text(fracture_source.read_text(encoding="utf-8"), encoding="utf-8")

# Start the effect on the local client's Time Erase rising edge, and let its own
# 60-tick client timer finish independently of the server-side ability duration.
replace_once(
    client,
    """                if (PKC.isUsingTimeErase()){
                    if (!isUsingTimeErase) {
                        ClientEffectUtil.terrainFragments.clear();
                    }
                    isUsingTimeErase = true;""",
    """                if (PKC.isUsingTimeErase()){
                    TimeEraseFractureEffect.tick(player, true);
                    if (!isUsingTimeErase) {
                        ClientEffectUtil.terrainFragments.clear();
                    }
                    isUsingTimeErase = true;""",
    "start local Time Erase fracture effect"
)
replace_once(
    client,
    """                } else {
                    isUsingTimeErase = false;
                    if (TimeErase > -1){
                        TimeErase--;
                    }
                }
            } else {
                isUsingTimeErase = false;
                if (TimeErase > -1){
                    TimeErase--;
                }
            }""",
    """                } else {
                    TimeEraseFractureEffect.tick(player, false);
                    isUsingTimeErase = false;
                    if (TimeErase > -1){
                        TimeErase--;
                    }
                }
            } else {
                TimeEraseFractureEffect.tick(player, false);
                isUsingTimeErase = false;
                if (TimeErase > -1){
                    TimeErase--;
                }
            }""",
    "advance local Time Erase fracture timer"
)

# Wire the fracture renderer into the same sky-return pass as the existing client
# terrain fragments. It renders after the native skybox and before the hidden terrain.
if "import net.hydra.jojomod.client.TimeEraseFractureEffect;" not in renderer.read_text(encoding="utf-8"):
    source = renderer.read_text(encoding="utf-8")
    marker = "import net.hydra.jojomod.client.ClientUtil;"
    if source.count(marker) != 1:
        raise SystemExit("import TimeEraseFractureEffect: expected one ClientUtil import")
    renderer.write_text(source.replace(marker, marker + "\nimport net.hydra.jojomod.client.TimeEraseFractureEffect;", 1), encoding="utf-8")

replace_once(
    renderer,
    "        buffer.endBatch();",
    "        TimeEraseFractureEffect.render($0, buffer, $3, $2);\n\n        buffer.endBatch();",
    "render local Time Erase block fractures and stone chips"
)

# Apply a strictly vertical, temporary pose offset at renderLevel HEAD and undo it
# at TAIL. No camera yaw/pitch/FOV or player position is modified.
replace_once(
    renderer,
    """        ClientUtil.mirrorCycles = 0;

        roundabout$renderAnubisMemory($$1);""",
    """        ClientUtil.mirrorCycles = 0;
        TimeEraseFractureEffect.applyCameraShake($$0, $$1);

        roundabout$renderAnubisMemory($$1);""",
    "apply vertical Time Erase camera shake"
)
replace_once(
    renderer,
    """        RenderCallbackRegistry.roundabout$LEVEL_RENDER_FINISH(partialTick);

    }""",
    """        RenderCallbackRegistry.roundabout$LEVEL_RENDER_FINISH(partialTick);
        TimeEraseFractureEffect.removeCameraShake($$0);

    }""",
    "restore render pose after Time Erase camera shake"
)

print("Applied: native Time Erase skybox plus local fracture wave, non-rotating stone chips, and a brief vertical shake.")
