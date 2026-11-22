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


# Replace Roundabout's random upward-flying blocks with a 360-degree radius sweep.
source = effect.read_text(encoding="utf-8")
start_marker = "    public static void spawnTerrainFragment(LocalPlayer player) {"
end_marker = "    public static void renderPurpleBox("
start = source.find(start_marker)
end = source.find(end_marker, start + len(start_marker))
if start < 0 or end < 0:
    raise SystemExit("Could not locate the original spawnTerrainFragment method.")

new_effect_method = """    public static void spawnTimeEraseShards(LocalPlayer player) {
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
        RandomSource random = level.random;

        // Sweep every direction around the player, limited to a 12-block radius.
        // Every ray selects the first solid surface it touches, never blocks behind a wall.
        final double radius = 12.0D;
        final double[] pitchAngles = {-0.62D, -0.40D, -0.20D, 0.0D, 0.20D, 0.40D, 0.62D};
        Set<BlockPos> surroundingBlocks = new LinkedHashSet<>();

        for (int yawIndex = 0; yawIndex < 72; yawIndex++) {
            double yaw = (Math.PI * 2.0D * yawIndex) / 72.0D;
            for (double pitch : pitchAngles) {
                double horizontal = Math.cos(pitch);
                Vec3 direction = new Vec3(
                        -Math.sin(yaw) * horizontal,
                        Math.sin(pitch),
                        Math.cos(yaw) * horizontal
                );
                Vec3 end = eye.add(direction.scale(radius));
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

                BlockPos pos = hit.getBlockPos().immutable();
                BlockState state = level.getBlockState(pos);
                if (!state.isAir()
                        && state.getRenderShape() == RenderShape.MODEL
                        && state.getFluidState().isEmpty()
                        && state.isSolidRender(level, pos)) {
                    surroundingBlocks.add(pos);
                }
            }
        }

        // One tetrahedral shard per surrounding surface block. Each shard has four
        // triangular faces, so the effect is angular debris instead of tiny cubes.
        terrainFragments.clear();
        for (BlockPos pos : surroundingBlocks) {
            BlockState state = level.getBlockState(pos);
            Vec3 blockCenter = Vec3.atCenterOf(pos);

            TerrainFragments fragment = new TerrainFragments();
            fragment.state = state;
            fragment.triangularShard = true;
            fragment.pos = blockCenter;
            fragment.prevPos = blockCenter;

            // Select a random corner and form a sharp tetrahedron inside the source block.
            double sx = random.nextBoolean() ? -0.43D : 0.43D;
            double sy = random.nextBoolean() ? -0.43D : 0.43D;
            double sz = random.nextBoolean() ? -0.43D : 0.43D;
            double shardSize = 0.30D + random.nextDouble() * 0.12D;
            fragment.shardA = new Vec3(sx, sy, sz);
            fragment.shardB = fragment.shardA.add(
                    -Math.signum(sx) * shardSize,
                    random.nextGaussian() * 0.025D,
                    random.nextGaussian() * 0.025D
            );
            fragment.shardC = fragment.shardA.add(
                    random.nextGaussian() * 0.025D,
                    -Math.signum(sy) * shardSize,
                    random.nextGaussian() * 0.025D
            );
            fragment.shardD = fragment.shardA.add(
                    random.nextGaussian() * 0.025D,
                    random.nextGaussian() * 0.025D,
                    -Math.signum(sz) * shardSize
            );

            Vec3 outward = blockCenter.subtract(player.position());
            if (outward.lengthSqr() < 1.0E-5D) {
                outward = new Vec3(
                        random.nextGaussian(),
                        random.nextGaussian() * 0.5D,
                        random.nextGaussian()
                );
            }
            outward = outward.normalize();
            // Hold the intact block in place during the crack phase.
            // Slow upward drift begins only after the crack animation.
            fragment.velocity = Vec3.ZERO;

            fragment.rotX = random.nextFloat() * 360.0F;
            fragment.rotY = random.nextFloat() * 360.0F;
            fragment.rotZ = random.nextFloat() * 360.0F;
            fragment.prevRotX = fragment.rotX;
            fragment.prevRotY = fragment.rotY;
            fragment.prevRotZ = fragment.rotZ;
            fragment.rotSpeedX = random.nextFloat() * 12.0F - 6.0F;
            fragment.rotSpeedY = random.nextFloat() * 12.0F - 6.0F;
            fragment.rotSpeedZ = random.nextFloat() * 12.0F - 6.0F;
            fragment.prevScale = 1.0F;
            fragment.scale = 1.0F;
            fragment.age = 0;
            fragment.maxAge = 96 + random.nextInt(18);
            terrainFragments.add(fragment);
        }
    }

"""
source = source[:start] + new_effect_method + source[end:]
effect.write_text(source, encoding="utf-8")

# Fire the debris burst once on the transition into Time Erase.
replace_once(
    client,
    """                if (PKC.isUsingTimeErase()){
                    if (clientTicker % 2 == 0) {
                        ClientEffectUtil.spawnTerrainFragment(player);
                    }
                    isUsingTimeErase = true;""",
    """                if (PKC.isUsingTimeErase()){
                    if (!isUsingTimeErase) {
                        ClientEffectUtil.spawnTimeEraseShards(player);
                    }
                    isUsingTimeErase = true;""",
    "one-shot 360-degree Time Erase shard trigger"
)

# Store a tetrahedron's four local vertices in each fragment.
replace_once(
    fragments,
    "    public BlockState state;\n",
    "    public BlockState state;\n    public boolean triangularShard;\n    public Vec3 shardA;\n    public Vec3 shardB;\n    public Vec3 shardC;\n    public Vec3 shardD;\n    public int crackTicks;\n",
    "TerrainFragments triangular shard geometry"
)

# Keep each original block visible while its surface cracks; only then lift its shards.
replace_once(
    effect,
    """            frag.velocity = frag.velocity.add(0, 0.005, 0);

            frag.pos = frag.pos.add(frag.velocity);""",
    """            if (frag.age >= 16) {
                // Slow, cinematic lift: ease upward instead of launching immediately.
                double lift = Math.min(0.010D, 0.0015D + (frag.age - 16) * 0.00012D);
                frag.velocity = frag.velocity.add(0, lift, 0);
                frag.velocity = new Vec3(
                        frag.velocity.x * 0.985D,
                        Math.min(frag.velocity.y, 0.035D),
                        frag.velocity.z * 0.985D
                );
                frag.pos = frag.pos.add(frag.velocity);
            }""",
    "crack pause and slow shard lift"
)

replace_once(
    effect,
    """            if (ClientUtil.isUsingTimeErase && frag.age < frag.maxAge) {
                frag.scale = 1.0F;
            } else {
                frag.scale = Math.max(0.0F, frag.scale - 0.05F);
            }""",
    """            if (ClientUtil.isUsingTimeErase && frag.age < frag.maxAge - 20) {
                frag.scale = 1.0F;
            } else {
                // Gradual evaporation rather than an abrupt disappearance.
                frag.scale = Math.max(0.0F, frag.scale - 0.035F);
            }""",
    "gradual shard evaporation"
)

# Replace the block-cube renderer with textured tetrahedral shard rendering.
s = renderer.read_text(encoding="utf-8")
start_marker = "    @Unique\n    public void rdbt$renderTerrainFragments("
end_marker = "    @Unique\n    public void rdbt$renderRTZBlockFragments("
start = s.find(start_marker)
end = s.find(end_marker, start + len(start_marker))
if start < 0 or end < 0:
    raise SystemExit("Could not locate terrain fragment renderer methods.")

new_renderer = """    @Unique
    public void rdbt$renderTerrainFragments(
            PoseStack poseStack,
            MultiBufferSource buffer,
            Camera camera,
            float partialTick
    ) {
        Player pl = Minecraft.getInstance().player;
        if (pl != null && (((TimeStop) pl.level()).inTimeStopRange(pl))) {
            partialTick = 0;
        }

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        Vec3 cam = camera.getPosition();
        Iterator<TerrainFragments> it = ClientEffectUtil.terrainFragments.iterator();

        while (it.hasNext()) {
            TerrainFragments frag = it.next();
            if (frag.scale <= 0) {
                it.remove();
                continue;
            }

            poseStack.pushPose();
            double x = Mth.lerp(partialTick, frag.prevPos.x, frag.pos.x) - cam.x;
            double y = Mth.lerp(partialTick, frag.prevPos.y, frag.pos.y) - cam.y;
            double z = Mth.lerp(partialTick, frag.prevPos.z, frag.pos.z) - cam.z;
            float scale = Mth.lerp(partialTick, frag.prevScale, frag.scale);

            poseStack.translate(x, y, z);
            float rx = Mth.lerp(partialTick, frag.prevRotX, frag.rotX);
            float ry = Mth.lerp(partialTick, frag.prevRotY, frag.rotY);
            float rz = Mth.lerp(partialTick, frag.prevRotZ, frag.rotZ);
            poseStack.mulPose(Axis.XP.rotationDegrees(rx));
            poseStack.mulPose(Axis.YP.rotationDegrees(ry));
            poseStack.mulPose(Axis.ZP.rotationDegrees(rz));

            if (frag.triangularShard && frag.age < 16) {
                // Crack stage: keep the source block in place before it breaks apart.
                poseStack.translate(-0.5, -0.5, -0.5);
                dispatcher.renderSingleBlock(
                        frag.state,
                        poseStack,
                        buffer,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY
                );
                poseStack.translate(0.5, 0.5, 0.5);
                rdbt$renderCrackLines(poseStack, buffer, frag.age);
            } else if (frag.triangularShard) {
                poseStack.scale(scale, scale, scale);
                net.minecraft.client.renderer.texture.TextureAtlasSprite sprite =
                        dispatcher.getBlockModelShaper().getParticleIcon(frag.state);
                VertexConsumer consumer = buffer.getBuffer(
                        RenderType.entityCutoutNoCull(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS)
                );
                Matrix4f matrix = poseStack.last().pose();
                org.joml.Matrix3f normalMatrix = poseStack.last().normal();

                rdbt$emitTriangle(consumer, matrix, normalMatrix, frag.shardA, frag.shardB, frag.shardC, sprite, 1.0F);
                rdbt$emitTriangle(consumer, matrix, normalMatrix, frag.shardA, frag.shardD, frag.shardB, sprite, 0.82F);
                rdbt$emitTriangle(consumer, matrix, normalMatrix, frag.shardA, frag.shardC, frag.shardD, sprite, 0.92F);
                rdbt$emitTriangle(consumer, matrix, normalMatrix, frag.shardB, frag.shardD, frag.shardC, sprite, 0.72F);
            } else {
                poseStack.translate(-0.5, -0.5, -0.5);
                poseStack.scale(scale, scale, scale);
                dispatcher.renderSingleBlock(
                        frag.state,
                        poseStack,
                        buffer,
                        LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY
                );
            }

            poseStack.popPose();
        }
    }

    @Unique
    private void rdbt$renderCrackLines(PoseStack poseStack, MultiBufferSource buffer, int age) {
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        Matrix4f matrix = poseStack.last().pose();
        float spread = Math.min(0.46F, 0.12F + age * 0.022F);
        float lift = 0.503F;
        // Three branching jagged fissures across the top face.
        rdbt$crackSegment(lines, matrix, -spread, lift, -0.08F, -0.05F, lift, 0.02F);
        rdbt$crackSegment(lines, matrix, -0.05F, lift, 0.02F, 0.12F, lift, 0.13F);
        rdbt$crackSegment(lines, matrix, 0.12F, lift, 0.13F, spread, lift, 0.30F);
        rdbt$crackSegment(lines, matrix, -0.05F, lift, 0.02F, -0.20F, lift, 0.24F);
        rdbt$crackSegment(lines, matrix, 0.12F, lift, 0.13F, 0.28F, lift, -0.12F);
    }

    @Unique
    private void rdbt$crackSegment(VertexConsumer lines, Matrix4f matrix,
                                   float ax, float ay, float az,
                                   float bx, float by, float bz) {
        lines.vertex(matrix, ax, ay, az).color(0.08F, 0.02F, 0.02F, 1.0F).endVertex();
        lines.vertex(matrix, bx, by, bz).color(0.08F, 0.02F, 0.02F, 1.0F).endVertex();
    }

    @Unique
    private void rdbt$emitTriangle(
            VertexConsumer consumer,
            Matrix4f matrix,
            org.joml.Matrix3f normalMatrix,
            Vec3 a,
            Vec3 b,
            Vec3 c,
            net.minecraft.client.renderer.texture.TextureAtlasSprite sprite,
            float shade
    ) {
        Vec3 normal = b.subtract(a).cross(c.subtract(a));
        if (normal.lengthSqr() < 1.0E-8D) {
            return;
        }
        normal = normal.normalize();

        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        int light = LightTexture.FULL_BRIGHT;

        consumer.vertex(matrix, (float) a.x, (float) a.y, (float) a.z)
                .color(shade, shade, shade, 1.0F).uv(u0, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(normalMatrix, (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
        consumer.vertex(matrix, (float) b.x, (float) b.y, (float) b.z)
                .color(shade, shade, shade, 1.0F).uv(u1, v1)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(normalMatrix, (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
        consumer.vertex(matrix, (float) c.x, (float) c.y, (float) c.z)
                .color(shade, shade, shade, 1.0F).uv(u0, v0)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light)
                .normal(normalMatrix, (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
    }

"""
s = s[:start] + new_renderer + s[end:]
renderer.write_text(s, encoding="utf-8")

# Hide the terrain only while the ability itself is active, not during its fade-out.
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
    "immediate terrain restoration at Time Erase end"
)

print("Applied: 360-degree nearby block shatter into textured triangular shards; terrain restores immediately after Time Erase.")
