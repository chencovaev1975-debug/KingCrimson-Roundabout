from pathlib import Path

root = Path(".")
effect = root / "common/src/main/java/net/hydra/jojomod/client/ClientEffectUtil.java"
client = root / "common/src/main/java/net/hydra/jojomod/client/ClientUtil.java"
fragments = root / "common/src/main/java/net/hydra/jojomod/event/TerrainFragments.java"
renderer = root / "common/src/main/java/net/hydra/jojomod/mixin/ZLevelRenderer.java"


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    source = path.read_text(encoding="utf-8")
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


# Replace Roundabout's random 30-block-radius, upward-flying block selection with
# one-time sampling of the block surfaces actually visible in the player's view.
source = effect.read_text(encoding="utf-8")
start_marker = "    public static void spawnTerrainFragment(LocalPlayer player) {"
end_marker = "    public static void renderPurpleBox("
start = source.find(start_marker)
end = source.find(end_marker, start + len(start_marker))
if start < 0 or end < 0:
    raise SystemExit("Could not locate the original spawnTerrainFragment method.")

visible_only_method = """    public static void spawnVisibleTerrainFragments(LocalPlayer player) {
        if (player == null) {
            return;
        }
        if (ConfigManager.getClientConfig() != null
                && ConfigManager.getClientConfig().generalSettings != null
                && !ConfigManager.getClientConfig().generalSettings.timeEraseBlocks) {
            return;
        }
        if (((TimeStop) player.level()).inTimeStopRange(player)) {
            return;
        }

        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F).normalize();
        Vec3 right = look.cross(new Vec3(0.0D, 1.0D, 0.0D));
        if (right.lengthSqr() < 1.0E-6D) {
            right = new Vec3(1.0D, 0.0D, 0.0D);
        } else {
            right = right.normalize();
        }
        Vec3 up = right.cross(look).normalize();

        // Each ray represents one sample across the camera's forward field of view.
        // A ray can only select the first colliding block, so blocks behind walls
        // or other blocks are never included.
        Set<BlockPos> visibleBlocks = new LinkedHashSet<>();
        for (int verticalSample = -2; verticalSample <= 2; verticalSample++) {
            for (int horizontalSample = -3; horizontalSample <= 3; horizontalSample++) {
                double horizontal = horizontalSample * 0.20D;
                double vertical = verticalSample * 0.20D;
                Vec3 direction = look
                        .add(right.scale(horizontal))
                        .add(up.scale(vertical))
                        .normalize();
                Vec3 end = eye.add(direction.scale(24.0D));

                net.minecraft.world.phys.BlockHitResult hit = level.clip(
                        new net.minecraft.world.level.ClipContext(
                                eye,
                                end,
                                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                                net.minecraft.world.level.ClipContext.Fluid.NONE,
                                player
                        )
                );

                if (hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
                    continue;
                }

                BlockPos blockPos = hit.getBlockPos().immutable();
                BlockState state = level.getBlockState(blockPos);
                if (!state.isAir()
                        && state.getRenderShape() == RenderShape.MODEL
                        && state.getFluidState().isEmpty()) {
                    visibleBlocks.add(blockPos);
                }
            }
        }

        // A single activation creates one burst; never keep sampling random terrain
        // while Time Erase is active.
        terrainFragments.clear();
        RandomSource random = level.random;
        List<BlockPos> visibleBlockList = new ArrayList<>(visibleBlocks);
        int blocksToSpawn = Math.min(18, visibleBlockList.size());
        for (int blockIndex = 0; blockIndex < blocksToSpawn; blockIndex++) {
            // Spread the cap evenly across the complete camera view, not just its lower-left area.
            int selectedIndex = blockIndex * visibleBlockList.size() / blocksToSpawn;
            BlockPos pos = visibleBlockList.get(selectedIndex);
            BlockState state = level.getBlockState(pos);
            Vec3 blockCenter = Vec3.atCenterOf(pos);

            // 3x3x3 = 27 tiny cubic chips for each visible block.
            for (int chipX = 0; chipX < 3; chipX++) {
                for (int chipY = 0; chipY < 3; chipY++) {
                    for (int chipZ = 0; chipZ < 3; chipZ++) {
                        TerrainFragments fragment = new TerrainFragments();
                        fragment.state = state;
                        fragment.chipX = chipX;
                        fragment.chipY = chipY;
                        fragment.chipZ = chipZ;
                        fragment.pos = blockCenter;
                        fragment.prevPos = blockCenter;

                        // Push each chip away from the block's center in 3D.
                        fragment.velocity = new Vec3(
                                (chipX - 1) * 0.018D + random.nextGaussian() * 0.005D,
                                (chipY - 1) * 0.018D + random.nextGaussian() * 0.005D,
                                (chipZ - 1) * 0.018D + random.nextGaussian() * 0.005D
                        );

                        fragment.rotX = random.nextFloat() * 360.0F;
                        fragment.rotY = random.nextFloat() * 360.0F;
                        fragment.rotZ = random.nextFloat() * 360.0F;
                        fragment.prevRotX = fragment.rotX;
                        fragment.prevRotY = fragment.rotY;
                        fragment.prevRotZ = fragment.rotZ;
                        fragment.rotSpeedX = random.nextFloat() * 8.0F - 4.0F;
                        fragment.rotSpeedY = random.nextFloat() * 8.0F - 4.0F;
                        fragment.rotSpeedZ = random.nextFloat() * 8.0F - 4.0F;
                        fragment.prevScale = 1.0F;
                        fragment.scale = 1.0F;
                        fragment.age = 0;
                        fragment.maxAge = 18 + random.nextInt(10);
                        terrainFragments.add(fragment);
                    }
                }
            }
        }
    }

"""
source = source[:start] + visible_only_method + source[end:]
effect.write_text(source, encoding="utf-8")

# Spawn the visible-block burst only on the transition into Time Erase.
replace_once(
    client,
    """                if (PKC.isUsingTimeErase()){
                    if (clientTicker % 2 == 0) {
                        ClientEffectUtil.spawnTerrainFragment(player);
                    }
                    isUsingTimeErase = true;""",
    """                if (PKC.isUsingTimeErase()){
                    if (!isUsingTimeErase) {
                        ClientEffectUtil.spawnVisibleTerrainFragments(player);
                    }
                    isUsingTimeErase = true;""",
    "one-shot Time Erase fragment trigger"
)

# Store the chip's cell inside the original block.
replace_once(
    fragments,
    "    public BlockState state;\n",
    "    public BlockState state;\n    public int chipX;\n    public int chipY;\n    public int chipZ;\n",
    "TerrainFragments chip coordinates"
)

# Render each fragment as a true 1/3-scale cube in its original 3x3x3 cell.
replace_once(
    renderer,
    """            // Center rotation
            poseStack.translate(-0.5, -0.5, -0.5);
            poseStack.scale(scale,scale,scale);""",
    """            // Place one 1/3-sized chip in its original 3x3x3 block cell.
            poseStack.translate(
                    -0.5 + frag.chipX / 3.0,
                    -0.5 + frag.chipY / 3.0,
                    -0.5 + frag.chipZ / 3.0
            );
            poseStack.scale(scale / 3.0F, scale / 3.0F, scale / 3.0F);""",
    "cubic chip render transform"
)

# Hide all normal chunk layers throughout Time Erase, including its fade-out.
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
    "hide normal world chunk layers during Time Erase"
)

# Keep clouds hidden until the Time Erase skybox fade has completely ended.
replace_once(
    renderer,
    "if (ClientUtil.isUsingTimeErase || ClientUtil.isUnderRTZ()) {",
    "if (ClientUtil.renderTimeErase() || ClientUtil.isUnderRTZ()) {",
    "cloud visibility during Time Erase fade"
)

print("Applied visible-only Time Erase block disintegration and cosmic-only terrain rendering.")
