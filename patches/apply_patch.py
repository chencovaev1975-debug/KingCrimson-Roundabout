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
        if (ClientUtil.isUsingTimeErase) {
            ci.cancel();
        }
    }""",
    "hide normal world chunks during Time Erase"
)

# Never render Roundabout's native red projection for StandEntity models during Time Erase.
# The matching future-image layer also excludes StandEntity, preventing a red duplicate
# of King Crimson while retaining projections of ordinary living entities.
replace_once(
    renderer,
    """                    if (entity instanceof LivingEntity lv){
                    Player pl = ClientUtil.getPlayer();""",
    """                    if (entity instanceof LivingEntity lv
                            && !(entity instanceof net.hydra.jojomod.entity.stand.StandEntity)){
                    Player pl = ClientUtil.getPlayer();""",
    "exclude StandEntity from native Time Erase red projections"
)

# Let the native Roundabout six-face skybox remain visible and keep clouds out of it.
replace_once(
    renderer,
    "if (ClientUtil.isUsingTimeErase || ClientUtil.isUnderRTZ()) {",
    "if (ClientUtil.isUsingTimeErase || ClientUtil.isUnderRTZ()) {",
    "hide clouds during the Time Erase skybox fade"
)

# Restore the native End sky on the same frame the Time Erase ability is released.
# Locate the injection by method name so this remains robust to local-variable names.
end_sky_source = renderer.read_text(encoding="utf-8")
end_sky_start = end_sky_source.find("private void roundabout$renderEndSky")
if end_sky_start < 0:
    raise SystemExit("End sky restoration: renderEndSky injection not found")
next_inject = end_sky_source.find("@Inject", end_sky_start + 1)
end_sky_condition = end_sky_source.find(
    "if (ClientUtil.renderTimeErase() || ClientUtil.isUnderRTZ()) {",
    end_sky_start
)
if end_sky_condition < 0 or (next_inject >= 0 and end_sky_condition > next_inject):
    raise SystemExit("End sky restoration: expected condition not found in renderEndSky")
end_sky_source = (
    end_sky_source[:end_sky_condition]
    + end_sky_source[end_sky_condition:].replace(
        "if (ClientUtil.renderTimeErase() || ClientUtil.isUnderRTZ()) {",
        "if (ClientUtil.isUsingTimeErase || ClientUtil.isUnderRTZ()) {",
        1
    )
)
renderer.write_text(end_sky_source, encoding="utf-8")

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
    "TimeEraseFutureLayer.java": root / "common/src/main/java/net/hydra/jojomod/client/models/layers/TimeEraseFutureLayer.java",
    "TimeEraseSecondEffect.java": root / "common/src/main/java/net/hydra/jojomod/client/TimeEraseSecondEffect.java",
    "time_erase_glitch_post.json": root / "common/src/main/resources/assets/minecraft/shaders/post/time_erase_glitch.json",
    "time_erase_glitch_program.json": root / "common/src/main/resources/assets/minecraft/shaders/program/time_erase_glitch.json",
    "time_erase_glitch.fsh": root / "common/src/main/resources/assets/minecraft/shaders/program/time_erase_glitch.fsh",
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
if "import net.hydra.jojomod.client.TimeEraseSecondEffect;" not in renderer.read_text(encoding="utf-8"):
    source = renderer.read_text(encoding="utf-8")
    marker = "import net.hydra.jojomod.client.TimeEraseFractureEffect;"
    if source.count(marker) != 1:
        raise SystemExit("import TimeEraseSecondEffect: expected one fracture-effect import")
    renderer.write_text(source.replace(marker, marker + "\nimport net.hydra.jojomod.client.TimeEraseSecondEffect;", 1), encoding="utf-8")

replace_once(
    renderer,
    "        buffer.endBatch();",
    "        TimeEraseFractureEffect.render($$0, buffer, $$3, $$2);\n        TimeEraseSecondEffect.render($$0, buffer, $$3, $$2);\n\n        buffer.endBatch();",
    "render local Time Erase block fractures and stone chips"
)

# Apply a strictly vertical, temporary pose offset at renderLevel HEAD and undo it
# at TAIL. No camera yaw/pitch/FOV or player position is modified.
replace_once(
    renderer,
    """        ClientUtil.mirrorCycles = 0;

        roundabout$renderAnubisMemory($$1);""",
    """        ClientUtil.mirrorCycles = 0;
        TimeEraseFractureEffect.applyCameraShake($$0, $$1);\n        TimeEraseSecondEffect.applyCameraShake($$0, $$1);

        roundabout$renderAnubisMemory($$1);""",
    "apply vertical Time Erase camera shake"
)
replace_once(
    renderer,
    """        RenderCallbackRegistry.roundabout$LEVEL_RENDER_FINISH(partialTick);

    }""",
    """        RenderCallbackRegistry.roundabout$LEVEL_RENDER_FINISH(partialTick);
        TimeEraseFractureEffect.removeCameraShake($$0);\n        TimeEraseSecondEffect.removeCameraShake($$0);

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

# Register the standalone second Time Erase on B and tick it on the local client.
key_registry = root / "common/src/main/java/net/hydra/jojomod/client/KeyInputRegistry.java"
update_source_once(
    key_registry,
    '    public static final String POWER_SWITCH = "key.roundabout.power_switch";',
    '''    public static final String POWER_SWITCH = "key.roundabout.power_switch";
    public static final String KEY_SECOND_TIME_ERASE = "key.roundabout.second_time_erase";''',
    "add second Time Erase key identifier"
)
update_source_once(
    key_registry,
    '''    public static KeyMapping power_switch = new KeyMapping(
            KeyInputRegistry.POWER_SWITCH,
            -1,
            KeyInputRegistry.KEY_CATEGORY_JOJO
    );''',
    '''    public static KeyMapping power_switch = new KeyMapping(
            KeyInputRegistry.POWER_SWITCH,
            -1,
            KeyInputRegistry.KEY_CATEGORY_JOJO
    );
    public static final KeyMapping secondTimeEraseKey = new KeyMapping(
            KeyInputRegistry.KEY_SECOND_TIME_ERASE,
            InputConstants.KEY_B,
            KeyInputRegistry.KEY_CATEGORY_JOJO
    );''',
    "bind second Time Erase to B"
)

fabric_keys = root / "fabric/src/main/java/net/hydra/jojomod/registry/FabricKeyInputs.java"
update_source_once(
    fabric_keys,
    "        KeyBindingHelper.registerKeyBinding(KeyInputRegistry.power_switch);",
    "        KeyBindingHelper.registerKeyBinding(KeyInputRegistry.power_switch);\n        KeyBindingHelper.registerKeyBinding(KeyInputRegistry.secondTimeEraseKey);",
    "register B key on Fabric"
)
forge_keys = root / "forge/src/main/java/net/hydra/jojomod/registry/ForgeKeyInputs.java"
update_source_once(
    forge_keys,
    "         event.register(KeyInputRegistry.power_switch);",
    "         event.register(KeyInputRegistry.power_switch);\n         event.register(KeyInputRegistry.secondTimeEraseKey);",
    "register B key on Forge"
)

client_util = root / "common/src/main/java/net/hydra/jojomod/client/ClientUtil.java"
update_source_once(
    client_util,
    "        ClientEffectUtil.updateTerrainFragments();",
    '''        if (player != null && Minecraft.getInstance().screen == null
                && KeyInputRegistry.secondTimeEraseKey.consumeClick()) {
            TimeEraseSecondEffect.start(player);
        }
        TimeEraseSecondEffect.tick(player);
        ClientEffectUtil.updateTerrainFragments();''',
    "start and tick the second scene from B input"
)

# Register the two user-provided Ogg clips on both project loaders.
mod_sounds = root / "common/src/main/java/net/hydra/jojomod/sound/ModSounds.java"
update_source_once(
    mod_sounds,
    "    public static SoundEvent TIME_ERASE_END_EVENT = SoundEvent.createVariableRangeEvent(TIME_ERASE_END_ID);",
    '''    public static SoundEvent TIME_ERASE_END_EVENT = SoundEvent.createVariableRangeEvent(TIME_ERASE_END_ID);
    public static final String TIME_ERASE_SECOND_INTRO = "time_erase_second_intro";
    public static final ResourceLocation TIME_ERASE_SECOND_INTRO_ID = new ResourceLocation(Roundabout.MOD_ID+":"+TIME_ERASE_SECOND_INTRO);
    public static SoundEvent TIME_ERASE_SECOND_INTRO_EVENT = SoundEvent.createVariableRangeEvent(TIME_ERASE_SECOND_INTRO_ID);
    public static final String TIME_ERASE_SECOND_MAIN = "time_erase_second_main";
    public static final ResourceLocation TIME_ERASE_SECOND_MAIN_ID = new ResourceLocation(Roundabout.MOD_ID+":"+TIME_ERASE_SECOND_MAIN);
    public static SoundEvent TIME_ERASE_SECOND_MAIN_EVENT = SoundEvent.createVariableRangeEvent(TIME_ERASE_SECOND_MAIN_ID);''',
    "declare second Time Erase sound events"
)
fabric_sounds = root / "fabric/src/main/java/net/hydra/jojomod/registry/FabricSounds.java"
update_source_once(
    fabric_sounds,
    "        addSound(ModSounds.TIME_ERASE_END_ID, ModSounds.TIME_ERASE_END_EVENT);",
    '''        addSound(ModSounds.TIME_ERASE_END_ID, ModSounds.TIME_ERASE_END_EVENT);
        addSound(ModSounds.TIME_ERASE_SECOND_INTRO_ID, ModSounds.TIME_ERASE_SECOND_INTRO_EVENT);
        addSound(ModSounds.TIME_ERASE_SECOND_MAIN_ID, ModSounds.TIME_ERASE_SECOND_MAIN_EVENT);''',
    "register second Time Erase sounds on Fabric"
)
forge_sounds = root / "forge/src/main/java/net/hydra/jojomod/registry/ForgeSounds.java"
update_source_once(
    forge_sounds,
    """    public static final RegistryObject<SoundEvent> TIME_ERASE_END =
            register(ModSounds.TIME_ERASE_END, ModSounds.TIME_ERASE_END_ID);""",
    """    public static final RegistryObject<SoundEvent> TIME_ERASE_END =
            register(ModSounds.TIME_ERASE_END, ModSounds.TIME_ERASE_END_ID);
    public static final RegistryObject<SoundEvent> TIME_ERASE_SECOND_INTRO =
            register(ModSounds.TIME_ERASE_SECOND_INTRO, ModSounds.TIME_ERASE_SECOND_INTRO_ID);
    public static final RegistryObject<SoundEvent> TIME_ERASE_SECOND_MAIN =
            register(ModSounds.TIME_ERASE_SECOND_MAIN, ModSounds.TIME_ERASE_SECOND_MAIN_ID);""",
    "register second Time Erase sounds on Forge"
)
sounds_json = root / "common/src/main/resources/assets/roundabout/sounds.json"
update_source_once(
    sounds_json,
    '''  "time_erase_end": {
    "subtitle": "sounds.roundabout.time_erase_end",
    "sounds": [
      "roundabout:time_erase_end"
    ]
  },''',
    '''  "time_erase_end": {
    "subtitle": "sounds.roundabout.time_erase_end",
    "sounds": [
      "roundabout:time_erase_end"
    ]
  },
  "time_erase_second_intro": {
    "sounds": [
      "roundabout:time_erase_second_intro"
    ]
  },
  "time_erase_second_main": {
    "sounds": [
      {
        "name": "roundabout:time_erase_second_main",
        "stream": true
      }
    ]
  },''',
    "add second Time Erase sound definitions"
)

en_lang = root / "common/src/main/resources/assets/roundabout/lang/en_us.json"
ru_lang = root / "common/src/main/resources/assets/roundabout/lang/ru_ru.json"
update_source_once(
    en_lang,
    '  "key.roundabout.power_switch": "Switch Powers",',
    '  "key.roundabout.power_switch": "Switch Powers",\n  "key.roundabout.second_time_erase": "Second Time Erase (B)",',
    "add English B key label"
)
update_source_once(
    ru_lang,
    '  "key.roundabout.power_switch": "Сменить способность",',
    '  "key.roundabout.power_switch": "Сменить способность",\n  "key.roundabout.second_time_erase": "Вторая Вырезка (B)",',
    "add Russian B key label"
)

# Register the post-processing shader for the second scene's horizontal
# pink/purple tears. It runs only during the opening and closing transitions.
post_shader_registry = root / "common/src/main/java/net/zetalasis/client/shader/RPostShaderRegistry.java"
update_source_once(
    post_shader_registry,
    "    public static IPostChainAccessor D4C_DIM_MERGE_LEAVE = null;",
    "    public static IPostChainAccessor D4C_DIM_MERGE_LEAVE = null;\\n    public static IPostChainAccessor TIME_ERASE_GLITCH = null;",
    "declare second Time Erase glitch post-chain"
)
update_source_once(
    post_shader_registry,
    "        D4C_DIM_MERGE_LEAVE = register(\"d4cdimmerge_leave\");",
    "        D4C_DIM_MERGE_LEAVE = register(\"d4cdimmerge_leave\");\\n        TIME_ERASE_GLITCH = register(\"time_erase_glitch\");",
    "register second Time Erase glitch post-chain"
)

shader_game_renderer = root / "common/src/main/java/net/hydra/jojomod/mixin/shaders/ShaderGameRenderer.java"
update_source_once(
    shader_game_renderer,
    "import net.hydra.jojomod.client.ClientUtil;",
    "import net.hydra.jojomod.client.ClientUtil;\\nimport net.hydra.jojomod.client.TimeEraseSecondEffect;",
    "import second Time Erase transition state"
)
update_source_once(
    shader_game_renderer,
    """            RPostShaderRegistry.D4C_DIM_MERGE_LEAVE.roundabout$process(tickDelta);
        }
    }

    @Inject(method = "reloadShaders", at=@At("HEAD"))""",
    """            RPostShaderRegistry.D4C_DIM_MERGE_LEAVE.roundabout$process(tickDelta);
        }

        float glitchProgress = TimeEraseSecondEffect.getTransitionProgress();
        if (glitchProgress >= 0.0F && RPostShaderRegistry.TIME_ERASE_GLITCH != null) {
            RPostShaderRegistry.TIME_ERASE_GLITCH.roundabout$setUniform("Progress", glitchProgress);
            RPostShaderRegistry.TIME_ERASE_GLITCH.roundabout$setUniform(
                    "ElapsedTime", TimeEraseSecondEffect.getElapsedSeconds());
            RPostShaderRegistry.TIME_ERASE_GLITCH.roundabout$process(tickDelta);
        }
    }

    @Inject(method = "reloadShaders", at=@At("HEAD"))""",
    "process second Time Erase spatial glitch"
)

# Silence the activating client's world audio during Time Erase while retaining
# only its activation/end sounds, Diavolo theme, and nearby King Crimson impacts.
sound_manager_mixin = root / "common/src/main/java/net/hydra/jojomod/mixin/soft_and_wet/SoftAndWetSoundManager.java"
update_source_once(
    sound_manager_mixin,
    "import net.hydra.jojomod.access.ILevelAccess;",
    """import net.hydra.jojomod.access.ILevelAccess;
import net.hydra.jojomod.client.TimeEraseFractureEffect;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.sounds.SoundSource;""",
    "import the local Time Erase sound filter"
)
update_source_once(
    sound_manager_mixin,
    """    public void roundabout$play(SoundInstance $$0, CallbackInfo ci) {
        if (Minecraft.getInstance().player != null){""",
    """    public void roundabout$play(SoundInstance $$0, CallbackInfo ci) {
        SoundInstance routed = TimeEraseFractureEffect.routeTimeEraseSound($$0);
        if (routed == null) {
            ci.cancel();
            return;
        }
        if (routed != $$0) {
            ci.cancel();
            Minecraft.getInstance().getSoundManager().play(routed);
            return;
        }
        if (Minecraft.getInstance().player != null){""",
    "filter and route ordinary world sounds during Time Erase"
)
sound_mixin_source = sound_manager_mixin.read_text(encoding="utf-8")
if "roundabout$filterTimeEraseDelayedSound" not in sound_mixin_source:
    close = sound_mixin_source.rfind("}")
    if close < 0:
        raise SystemExit("Time Erase audio filter: SoundManager mixin closing brace not found")
    sound_injections = """
    @Inject(method = "playDelayed", at = @At("HEAD"), cancellable = true)
    public void roundabout$filterTimeEraseDelayedSound(SoundInstance sound, int delay, CallbackInfo ci) {
        SoundInstance routed = TimeEraseFractureEffect.routeTimeEraseSound(sound);
        if (routed == null) {
            ci.cancel();
        } else if (routed != sound) {
            ci.cancel();
            Minecraft.getInstance().getSoundManager().playDelayed(routed, delay);
        }
    }

    @Inject(method = "queueTickingSound", at = @At("HEAD"), cancellable = true)
    public void roundabout$filterTimeEraseMasterLoop(TickableSoundInstance sound, CallbackInfo ci) {
        if (TimeEraseFractureEffect.isWorldAudioMuted()
                && sound.getSource() == SoundSource.MASTER
                && !TimeEraseFractureEffect.isAllowedTimeEraseSound(sound)) {
            ci.cancel();
        }
    }

"""
    sound_manager_mixin.write_text(
        sound_mixin_source[:close] + sound_injections + sound_mixin_source[close:],
        encoding="utf-8"
    )

# Attach only the red future-image layer.
living_renderer = root / "common/src/main/java/net/hydra/jojomod/mixin/ZLivingEntityRenderer.java"
update_source_once(
    living_renderer,
    "import net.hydra.jojomod.client.models.layers.FrozenLayer;",
    """import net.hydra.jojomod.client.models.layers.FrozenLayer;
import net.hydra.jojomod.client.models.layers.TimeEraseFutureLayer;""",
    "import only the Time Erase future-image layer"
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
    "        this.addLayer(new TimeEraseFutureLayer<>(this));",
    "attach the future hologram to living renderers"
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
    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void roundabout$renderTimeEraseSlash(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        if (this.minecraft.player != null) {
            TimeEraseSlashRenderer.render(graphics, partialTick, this.screenWidth, this.screenHeight);
        }
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

update_source_once(
    powers,
    """    public int getTimeEraseCooldown(){
        int maxTicks = timeEraseMaxTicks();
        int ticksEaten = maxTicks - ticksOfEraseLeft;
        ticksEaten = Math.max(ticksEaten,0);

        int cooldownOverall = ClientNetworking.getAppropriateConfig().
                kingCrimsonSettings.timeEraseMinimumCooldown;
        cooldownOverall += (int)(((float)ticksEaten)
                *((ClientNetworking.getAppropriateConfig().kingCrimsonSettings.
                additionalCooldownPerSecondsUsed2 *0.05)));

        if (isBeyondRange()) {
            cooldownOverall+=ClientNetworking.getAppropriateConfig().
                    kingCrimsonSettings.additionalCooldownFromPlayerRunning;
        }

        return cooldownOverall;
    }""",
    """    public int getTimeEraseCooldown(){
        return 30 * 20;
    }""",
    "set Time Erase cooldown to 30 seconds"
)

update_source_once(
    powers,
    """    public int getTimeEraseCooldownMax(){
        int maxTicks = timeEraseMaxTicks();
        int ticksEaten = maxTicks;
        ticksEaten = Math.max(ticksEaten,0);

        int cooldownOverall = ClientNetworking.getAppropriateConfig().
                kingCrimsonSettings.timeEraseMinimumCooldown;
        cooldownOverall += (int)(((float)ticksEaten)
                *((ClientNetworking.getAppropriateConfig().kingCrimsonSettings.
                additionalCooldownPerSecondsUsed2 *0.05)));

        if (isBeyondRange()) {
            cooldownOverall+=ClientNetworking.getAppropriateConfig().
                    kingCrimsonSettings.additionalCooldownFromPlayerRunning;
        }

        return cooldownOverall;
    }""",
    """    public int getTimeEraseCooldownMax(){
        return 30 * 20;
    }""",
    "set Time Erase maximum cooldown display to 30 seconds"
)

update_source_once(
    powers,
    """            int minCo = ClientNetworking.getAppropriateConfig().
                    kingCrimsonSettings.timeEraseMinimumCooldown;""",
    """            int minCo = 30 * 20;""",
    "keep Time Erase cooldown at 30 seconds after switching back to the stand"
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


# Copy the glitch post-chain and program resources into Minecraft's shader namespace.
shader_assets = {
    "time_erase_glitch_post.json": root / "common/src/main/resources/assets/minecraft/shaders/post/time_erase_glitch.json",
    "time_erase_glitch_program.json": root / "common/src/main/resources/assets/minecraft/shaders/program/time_erase_glitch.json",
    "time_erase_glitch.fsh": root / "common/src/main/resources/assets/minecraft/shaders/program/time_erase_glitch.fsh",
}
for source_name, target_path in shader_assets.items():
    source_path = Path(__file__).with_name(source_name)
    if not source_path.exists():
        raise SystemExit(f"Missing second Time Erase shader asset: patches/{source_name}")
    target_path.parent.mkdir(parents=True, exist_ok=True)
    target_path.write_text(source_path.read_text(encoding="utf-8"), encoding="utf-8")

# Install the Diavolo theme and the two supplied second Time Erase recordings.
assets_dir = Path(__file__).with_name("assets")
audio_targets = {
    "JoJo_s-Bizarre-Adventure_-Golden-Wind-—-Diavolo-theme-_online-video-cutter.com_.ogg": "diavolo_theme.ogg",
    "time_erase_second_intro.ogg": "time_erase_second_intro.ogg",
    "time_erase_second_main.ogg": "time_erase_second_main.ogg",
}
for source_name, target_name in audio_targets.items():
    audio_source = assets_dir / source_name
    if not audio_source.exists():
        raise SystemExit(f"Missing audio resource: patches/assets/{source_name}")
    audio_bytes = audio_source.read_bytes()
    if not audio_bytes.startswith(b"OggS"):
        raise SystemExit(f"Audio resource is not a valid Ogg container: {source_name}")
    audio_target = root / "common/src/main/resources/assets/roundabout/sounds" / target_name
    audio_target.parent.mkdir(parents=True, exist_ok=True)
    audio_target.write_bytes(audio_bytes)
    print(f"Installed Ogg audio resource: {source_name} -> {target_name}.")
print("Applied the 30-block exposed-scene snapshot and the ground-launched giant stone burst.")

