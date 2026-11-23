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

# Install the screen slash and the two player/entity render layers.
addon_files = {
    "TimeEraseSlashRenderer.java": root / "common/src/main/java/net/hydra/jojomod/client/TimeEraseSlashRenderer.java",
    "TimeEraseAuraLayer.java": root / "common/src/main/java/net/hydra/jojomod/client/models/layers/TimeEraseAuraLayer.java",
    "TimeEraseFutureLayer.java": root / "common/src/main/java/net/hydra/jojomod/client/models/layers/TimeEraseFutureLayer.java",
}
for filename, target in addon_files.items():
    source = Path(__file__).with_name(filename)
    if not source.exists():
        raise SystemExit(f"Missing addon source: patches/{filename}")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(source.read_text(encoding="utf-8"), encoding="utf-8")

# Generate two tiny white/alpha textures locally. The tint and alpha are supplied
# by the render layers, so no external image dependency or network fetch is needed.
def write_rgba_png(path, width, height, pixel_fn):
    import struct
    import zlib

    def chunk(kind, data):
        return (struct.pack(">I", len(data)) + kind + data
                + struct.pack(">I", zlib.crc32(kind + data) & 0xffffffff))

    rows = bytearray()
    for y in range(height):
        rows.append(0)  # PNG filter: None
        for x in range(width):
            rows.extend(pixel_fn(x, y))
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
           + chunk(b"IEND", b""))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(png)

texture_root = root / "common/src/main/resources/assets/roundabout/textures/misc"
write_rgba_png(texture_root / "flat_white.png", 1, 1,
               lambda x, y: (255, 255, 255, 255))

def aura_pixel(x, y):
    import math
    vertical = y / 63.0
    alpha = 0
    for index in range(5):
        base = 5.0 + index * 13.5
        center = base + math.sin(vertical * 8.5 + index * 1.63) * 3.2
        width = 2.2 + 1.6 * math.sin(vertical * math.pi)
        distance = abs(x - center)
        if distance < width:
            flame_alpha = int(230 * (1.0 - distance / width) ** 0.7
                              * (0.30 + 0.70 * vertical))
            alpha = max(alpha, flame_alpha)
    # A few fine wisps break up the broad tongues into flowing energy streaks.
    for index in range(3):
        center = 16.0 + index * 17.0 + math.sin(vertical * 13.0 + index) * 5.0
        distance = abs(x - center)
        if distance < 1.2:
            alpha = max(alpha, int(125 * (1.0 - distance / 1.2) * vertical))
    return 255, 255, 255, alpha

write_rgba_png(texture_root / "diavolo_aura.png", 64, 64, aura_pixel)

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

# Attach the fullbright future hologram to every living renderer and the aura to
# the activating player's renderer. The normal model pose is reused, preserving
# each entity's current walk, jump, and attack animation.
living_renderer = root / "common/src/main/java/net/hydra/jojomod/mixin/ZLivingEntityRenderer.java"
update_source_once(
    living_renderer,
    "import net.hydra.jojomod.client.models.layers.FrozenLayer;",
    """import net.hydra.jojomod.client.models.layers.FrozenLayer;
import net.hydra.jojomod.client.models.layers.TimeEraseAuraLayer;
import net.hydra.jojomod.client.models.layers.TimeEraseFutureLayer;""",
    "import the Time Erase player aura and future render layers"
)
def insert_line_after_unique(path, marker, line_to_insert, label):
    lines = path.read_text(encoding="utf-8").splitlines()
    matches = [i for i, line in enumerate(lines) if marker in line]
    if len(matches) != 1:
        raise SystemExit(f"{label}: expected one line marker, found {len(matches)}")
    lines.insert(matches[0] + 1, line_to_insert)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")

insert_line_after_unique(
    living_renderer,
    "this.addLayer(new FrozenLayer<>",
    "        this.addLayer(new TimeEraseAuraLayer<>(this));\n        this.addLayer(new TimeEraseFutureLayer<>(this));",
    "attach the aura and future hologram to all living renderers"
)

update_source_once(
    living_renderer,
    """    private void roundabout$applyInvisibilityFade(T entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {

        byte sam = ((StandUser)entity).roundabout$getStandAnimation();""",
    """    private void roundabout$applyInvisibilityFade(T entity, float entityYaw, float partialTicks, PoseStack poseStack, MultiBufferSource buffer, int packedLight, CallbackInfo ci) {

        // Render the real body a small step along its predicted path, behind the
        // 1.25-second red future image. This changes only the client model pose;
        // the real entity position, hitbox, collision and server state are intact.
        net.minecraft.world.phys.Vec3 visualOffset =
                TimeEraseFractureEffect.getVisualBodyOffset(entity, partialTicks);
        if (visualOffset != null) {
            poseStack.translate(visualOffset.x, visualOffset.y, visualOffset.z);
        }

        byte sam = ((StandUser)entity).roundabout$getStandAnimation();""",
    "move the rendered body slightly behind its future hologram"
)

# Draw the neon slash after the native hotbar GUI. This is the 1.20.1 Gui render
# hook used by both project loaders; it keeps the effect in true screen-space.
hud_mixin = root / "common/src/main/java/net/hydra/jojomod/mixin/HudRendering.java"
update_source_once(
    hud_mixin,
    "import net.hydra.jojomod.client.ClientUtil;",
    """import net.hydra.jojomod.client.ClientUtil;
import net.hydra.jojomod.client.TimeEraseSlashRenderer;""",
    "import the screen-space Time Erase slash renderer"
)
hud_source = hud_mixin.read_text(encoding="utf-8")
if "roundabout$renderTimeEraseSlash" not in hud_source:
    close = hud_source.rfind("}")
    if close < 0:
        raise SystemExit("HUD slash injection: class closing brace not found")
    hud_inject = """
    @Inject(method = "renderHotbar", at = @At("TAIL"))
    private void roundabout$renderTimeEraseSlash(float partialTick, GuiGraphics graphics, CallbackInfo ci) {
        TimeEraseSlashRenderer.render(graphics, partialTick, this.screenWidth, this.screenHeight);
    }

"""
    hud_mixin.write_text(hud_source[:close] + hud_inject + hud_source[close:], encoding="utf-8")

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
print("Applied the 30-block exposed-scene snapshot and the ground-launched giant stone burst.")

