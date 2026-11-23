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

# Muted charcoal tint for the six Time Erase skybox faces.
sky_source = renderer.read_text(encoding="utf-8")
sky_start = sky_source.find('@Inject(method = "renderSky"')
sky_end = sky_source.find("if (ClientUtil.isUnderRTZ())", sky_start)
if sky_start < 0 or sky_end < 0:
    raise SystemExit("Time Erase skybox tint: could not locate renderSky section")
sky_section = sky_source[sky_start:sky_end]
tint_anchor = "color(1F, 1F, 1F, alpha)"
tint_count = sky_section.count(tint_anchor)
if tint_count != 4:
    raise SystemExit(f"Time Erase skybox tint: expected four vertex colors, found {tint_count}")
sky_section = sky_section.replace(tint_anchor, "color(0.30F, 0.31F, 0.33F, alpha)")
renderer.write_text(sky_source[:sky_start] + sky_section + sky_source[sky_end:], encoding="utf-8")



# Add the local-only fracture effect. The class is deliberately client-only and only
# snapshots block states: it never edits blocks, entities, collision, or server state.
fracture_class = root / "common/src/main/java/net/hydra/jojomod/client/TimeEraseFractureEffect.java"
fracture_class.parent.mkdir(parents=True, exist_ok=True)
fracture_source = Path(__file__).with_name("TimeEraseFractureEffect.java")
if not fracture_source.exists():
    raise SystemExit("Missing addon source: patches/TimeEraseFractureEffect.java")
fracture_class.write_text(fracture_source.read_text(encoding="utf-8"), encoding="utf-8")

# Start the effect on the local client's Time Erase rising edge, and let its own
# incremental client snapshot timer advance independently of server-side world data.
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
    "        TimeEraseFractureEffect.render($$0, buffer, $$3, $$2);\n\n        buffer.endBatch();",
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


# Add the user's second, client-local Time Erase theme as a separate sound.
# The activation event itself remains untouched.
def update_source_once(path, old, new, label):
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one anchor, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")

powers = root / "common/src/main/java/net/hydra/jojomod/stand/powers/PowersKingCrimson.java"
update_source_once(
    powers,
    "return ClientNetworking.getAppropriateConfig().kingCrimsonSettings.timeEraseDuration;",
    "return 28 * 20;",
    "set Time Erase to exactly 28 seconds"
)

mod_sounds = root / "common/src/main/java/net/hydra/jojomod/sound/ModSounds.java"
update_source_once(
    mod_sounds,
    "    public static SoundEvent TIME_ERASE_END_EVENT = SoundEvent.createVariableRangeEvent(TIME_ERASE_END_ID);",
    """    public static SoundEvent TIME_ERASE_END_EVENT = SoundEvent.createVariableRangeEvent(TIME_ERASE_END_ID);
    public static final String DIAVOLO_THEME = "diavolo_theme";
    public static final ResourceLocation DIAVOLO_THEME_ID = new ResourceLocation(Roundabout.MOD_ID+":"+DIAVOLO_THEME);
    public static SoundEvent DIAVOLO_THEME_EVENT = SoundEvent.createVariableRangeEvent(DIAVOLO_THEME_ID);""",
    "register Diavolo theme event"
)

fabric_sounds = root / "fabric/src/main/java/net/hydra/jojomod/registry/FabricSounds.java"
update_source_once(
    fabric_sounds,
    "        addSound(ModSounds.TIME_ERASE_END_ID, ModSounds.TIME_ERASE_END_EVENT);",
    """        addSound(ModSounds.TIME_ERASE_END_ID, ModSounds.TIME_ERASE_END_EVENT);
        addSound(ModSounds.DIAVOLO_THEME_ID, ModSounds.DIAVOLO_THEME_EVENT);""",
    "register Diavolo theme on Fabric"
)

sounds_json = root / "common/src/main/resources/assets/roundabout/sounds.json"
update_source_once(
    sounds_json,
    """  "time_erase_end": {""",
    """  "diavolo_theme": {
    "sounds": [
      "roundabout:diavolo_theme"
    ]
  },
  "time_erase_end": {""",
    "add Diavolo theme sound definition"
)

# Install the user's separately uploaded Diavolo theme. Do not alter the
# native Time Erase activation sound here.
assets_dir = Path(__file__).with_name("assets")
audio_candidates = sorted(assets_dir.glob("*.ogg"))
if len(audio_candidates) != 1:
    raise SystemExit("Expected exactly one .ogg file in patches/assets/; found: "
                     + ", ".join(path.name for path in audio_candidates))
audio_asset = audio_candidates[0]
audio_bytes = audio_asset.read_bytes()
if not audio_bytes.startswith(b"OggS"):
    raise SystemExit("Diavolo theme asset is not a valid Ogg container")
audio_target = root / "common/src/main/resources/assets/roundabout/sounds/diavolo_theme.ogg"
audio_target.parent.mkdir(parents=True, exist_ok=True)
audio_target.write_bytes(audio_bytes)
print(f"Installed Diavolo theme audio asset from {audio_asset.name}.")
print("Applied the 20-block exposed-scene snapshot and the faceted stone burst.")

