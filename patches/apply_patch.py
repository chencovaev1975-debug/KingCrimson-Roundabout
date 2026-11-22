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
        if (((TimeStop) player.level()).inTimeStopRange(player)) {
            return;
        }

        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        RandomSource random = level.random;

        // Collect exposed solid blocks in a compact sphere around the player.
        // This does not depend on the optional Roundabout terrain-fragment setting.
        final int radius = 7;
        final int minY = Mth.floor(player.getY()) - 3;
        final int maxY = Mth.floor(player.getY()) + 5;
        Set<BlockPos> surroundingBlocks = new LinkedHashSet<>();
        BlockPos center = player.blockPosition();
        outer:
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL
                            || !state.getFluidState().isEmpty() || !state.isSolidRender(level, pos)) {
                        continue;
                    }
                    boolean exposed = false;
                    for (Direction direction : Direction.values()) {
                        if (level.getBlockState(pos.relative(direction)).isAir()) {
                            exposed = true;
                            break;
                        }
                    }
                    if (exposed) {
                        surroundingBlocks.add(pos.immutable());
                        if (surroundingBlocks.size() >= 180) break outer;
                    }
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
    """    public static void updateTerrainFragments() {
        if (ConfigManager.getClientConfig() != null && ConfigManager.getClientConfig().generalSettings != null &&
                !ConfigManager.getClientConfig().generalSettings.timeEraseBlocks){
            return;
        }
""",
    """    public static void updateTerrainFragments() {
""",
    "always update the custom Time Erase fragments"
)

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

            double x = Mth.lerp(partialTick, frag.prevPos.x, frag.pos.x) - cam.x;
            double y = Mth.lerp(partialTick, frag.prevPos.y, frag.pos.y) - cam.y;
            double z = Mth.lerp(partialTick, frag.prevPos.z, frag.pos.z) - cam.z;
            float rx = Mth.lerp(partialTick, frag.prevRotX, frag.rotX);
            float ry = Mth.lerp(partialTick, frag.prevRotY, frag.rotY);
            float rz = Mth.lerp(partialTick, frag.prevRotZ, frag.rotZ);

            poseStack.pushPose();
            poseStack.translate(x, y, z);

            if (frag.age < 16) {
                // Stage 1: the original block stays intact while visible fissures spread.
                poseStack.pushPose();
                poseStack.translate(-0.5, -0.5, -0.5);
                dispatcher.renderSingleBlock(
                        frag.state, poseStack, buffer,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY
                );
                poseStack.popPose();
                rdbt$renderCrackLines(poseStack, buffer, frag.age);
            } else {
                // Stage 2: split the block into eight clearly visible textured chunks.
                // Each chunk separates and rises slowly; the source world block is untouched.
                float elapsed = frag.age - 16;
                float spread = Math.min(0.20F, elapsed * 0.006F);
                float rise = Math.min(0.18F, elapsed * 0.005F);
                for (int ix = 0; ix < 2; ix++) {
                    for (int iy = 0; iy < 2; iy++) {
                        for (int iz = 0; iz < 2; iz++) {
                            poseStack.pushPose();
                            float sx = ix == 0 ? -1.0F : 1.0F;
                            float sy = iy == 0 ? -1.0F : 1.0F;
                            float sz = iz == 0 ? -1.0F : 1.0F;
                            poseStack.translate(
                                    -0.5F + ix * 0.5F + sx * spread,
                                    -0.5F + iy * 0.5F + rise + Math.max(0.0F, sy) * spread,
                                    -0.5F + iz * 0.5F + sz * spread
                            );
                            poseStack.mulPose(Axis.XP.rotationDegrees(rx * 0.18F + sx * elapsed * 0.8F));
                            poseStack.mulPose(Axis.YP.rotationDegrees(ry * 0.18F + sy * elapsed * 0.9F));
                            poseStack.mulPose(Axis.ZP.rotationDegrees(rz * 0.18F + sz * elapsed * 0.7F));
                            poseStack.scale(0.5F, 0.5F, 0.5F);
                            dispatcher.renderSingleBlock(
                                    frag.state, poseStack, buffer,
                                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY
                            );
                            poseStack.popPose();
                        }
                    }
                }
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
        // Dark, branching fissures on the upper surface, widening during the crack phase.
        rdbt$crackSegment(lines, matrix, -spread, lift, -0.08F, -0.05F, lift, 0.02F);
        rdbt$crackSegment(lines, matrix, -0.05F, lift, 0.02F, 0.12F, lift, 0.13F);
        rdbt$crackSegment(lines, matrix, 0.12F, lift, 0.13F, spread, lift, 0.30F);
        rdbt$crackSegment(lines, matrix, -0.05F, lift, 0.02F, -0.20F, lift, 0.24F);
        rdbt$crackSegment(lines, matrix, 0.12F, lift, 0.13F, 0.28F, lift, -0.12F);
        rdbt$crackSegment(lines, matrix, 0.12F, lift, 0.13F, 0.04F, lift, -0.28F);
    }

    @Unique
    private void rdbt$crackSegment(VertexConsumer lines, Matrix4f matrix,
                                   float ax, float ay, float az,
                                   float bx, float by, float bz) {
        lines.vertex(matrix, ax, ay, az).color(0.03F, 0.01F, 0.01F, 1.0F).endVertex();
        lines.vertex(matrix, bx, by, bz).color(0.03F, 0.01F, 0.01F, 1.0F).endVertex();
    }

    private void rdbt$renderKingCrimsonAura(PoseStack poseStack, MultiBufferSource buffer,
                                                Camera camera, float partialTick) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !(((StandUser) player).roundabout$getStand() instanceof KingCrimsonEntity stand)
                || !stand.isAlive() || stand.isRemoved()) {
            return;
        }

        Vec3 cam = camera.getPosition();
        double x = Mth.lerp(partialTick, player.xOld, player.getX()) - cam.x;
        double y = Mth.lerp(partialTick, player.yOld, player.getY()) - cam.y;
        double z = Mth.lerp(partialTick, player.zOld, player.getZ()) - cam.z;
        float pulse = 0.92F + Mth.sin((ClientUtil.clientTicker + partialTick) * 0.22F) * 0.08F;
        VertexConsumer lines = buffer.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(x, y, z);
        Matrix4f matrix = poseStack.last().pose();

        // Three pulsing red rings and rising energy filaments form a full-body aura.
        float[] heights = {0.10F, 0.78F, 1.55F, 1.82F};
        for (int ring = 0; ring < heights.length; ring++) {
            float radius = (ring == 0 ? 0.52F : ring == 1 ? 0.43F : 0.34F) * pulse;
            rdbt$renderAuraRing(lines, matrix, heights[ring], radius, ring * 0.22F);
        }
        for (int i = 0; i < 12; i++) {
            float angle = (float) (Math.PI * 2.0D * i / 12.0D)
                    + (ClientUtil.clientTicker + partialTick) * 0.035F;
            float radius = 0.43F * pulse;
            float x1 = Mth.cos(angle) * radius;
            float z1 = Mth.sin(angle) * radius;
            float x2 = Mth.cos(angle + 0.16F) * (radius * 0.72F);
            float z2 = Mth.sin(angle + 0.16F) * (radius * 0.72F);
            lines.vertex(matrix, x1, 0.12F, z1).color(1.0F, 0.015F, 0.025F, 0.88F).endVertex();
            lines.vertex(matrix, x2, 1.78F, z2).color(0.75F, 0.0F, 0.015F, 0.12F).endVertex();
        }
        poseStack.popPose();
    }

    @Unique
    private void rdbt$renderAuraRing(VertexConsumer lines, Matrix4f matrix,
                                    float y, float radius, float phase) {
        final int segments = 40;
        for (int i = 0; i < segments; i++) {
            float a0 = (float) (Math.PI * 2.0D * i / segments) + phase;
            float a1 = (float) (Math.PI * 2.0D * (i + 1) / segments) + phase;
            lines.vertex(matrix, Mth.cos(a0) * radius, y, Mth.sin(a0) * radius)
                    .color(1.0F, 0.015F, 0.025F, 0.78F).endVertex();
            lines.vertex(matrix, Mth.cos(a1) * radius, y, Mth.sin(a1) * radius)
                    .color(0.82F, 0.0F, 0.01F, 0.25F).endVertex();
        }
    }

"""
s = s[:start] + new_renderer + s[end:]
renderer.write_text(s, encoding="utf-8")

replace_once(
    renderer,
    """        rdbt$renderTerrainFragments(
                $0,
                buffer,
                $3,
                $2
        );

        buffer.endBatch();""",
    """        rdbt$renderTerrainFragments(
                $0,
                buffer,
                $3,
                $2
        );
        rdbt$renderKingCrimsonAura($0, buffer, $3, $2);
        buffer.endBatch();
        RenderSystem.lineWidth(1.0F);""",
    "render the King Crimson aura"
)

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
