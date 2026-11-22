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
        if (player == null || ((TimeStop) player.level()).inTimeStopRange(player)) {
            return;
        }
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle().normalize();
        final int radius = 40;
        final int verticalRadius = 24;
        final double minimumViewDot = 0.34D;
        BlockPos center = player.blockPosition();
        List<BlockPos> visibleBlocks = new ArrayList<>();

        // Scan a wide cone in the direction the player is actually looking.
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int y = center.getY() - verticalRadius; y <= center.getY() + verticalRadius; y++) {
                    BlockPos pos = new BlockPos(center.getX() + dx, y, center.getZ() + dz);
                    Vec3 toBlock = Vec3.atCenterOf(pos).subtract(eye);
                    double distanceSqr = toBlock.lengthSqr();
                    if (distanceSqr < 0.04D || distanceSqr > (double) radius * radius) continue;
                    if (toBlock.dot(look) / Math.sqrt(distanceSqr) < minimumViewDot) continue;

                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || state.getRenderShape() != RenderShape.MODEL
                            || !state.getFluidState().isEmpty() || !state.isSolidRender(level, pos)) continue;

                    boolean exposed = false;
                    for (Direction direction : Direction.values()) {
                        BlockPos neighborPos = pos.relative(direction);
                        BlockState neighbor = level.getBlockState(neighborPos);
                        if (neighbor.isAir() || !neighbor.isSolidRender(level, neighborPos)) {
                            exposed = true;
                            break;
                        }
                    }
                    if (exposed) visibleBlocks.add(pos.immutable());
                }
            }
        }

        visibleBlocks.sort(Comparator.comparingDouble(pos -> Vec3.atCenterOf(pos).distanceToSqr(eye)));
        if (visibleBlocks.size() > 2200) {
            visibleBlocks = new ArrayList<>(visibleBlocks.subList(0, 2200));
        }

        terrainFragments.clear();
        for (BlockPos pos : visibleBlocks) {
            Vec3 blockCenter = Vec3.atCenterOf(pos);
            Vec3 outward = blockCenter.subtract(player.position());
            if (outward.lengthSqr() < 1.0E-5D) outward = look;
            outward = outward.normalize();

            TerrainFragments fragment = new TerrainFragments();
            fragment.state = level.getBlockState(pos);
            fragment.pos = blockCenter;
            fragment.prevPos = blockCenter;
            fragment.velocity = outward.scale(0.025D).add(0.0D, 0.012D, 0.0D);
            fragment.fractureSeed = pos.hashCode();
            int delay = (int) Math.round(Math.sqrt(blockCenter.distanceToSqr(eye)) * 0.5D);
            fragment.age = -delay;
            fragment.maxAge = 24;
            fragment.scale = 1.0F;
            fragment.prevScale = 1.0F;
            fragment.rotX = fragment.rotY = fragment.rotZ = 0.0F;
            fragment.prevRotX = fragment.prevRotY = fragment.prevRotZ = 0.0F;
            fragment.rotSpeedX = fragment.rotSpeedY = fragment.rotSpeedZ = 0.0F;
            terrainFragments.add(fragment);
        }
    }

"""
source = source[:start] + new_effect_method + source[end:]
effect.write_text(source, encoding="utf-8")

replace_once(
    fragments,
    """    public int maxAge;""",
    """    public int maxAge;\n    public int fractureSeed;""",
    "deterministic per-block fracture seed"
)

replace_once(
    effect,
    """import net.minecraft.core.Direction;""",
    """import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;""",
    "red aura particle import"
)
replace_once(
    effect,
    """import org.joml.Matrix4f;""",
    """import org.joml.Matrix4f;
import org.joml.Vector3f;""",
    "red aura vector import"
)
replace_once(
    effect,
    """    public static void renderPurpleBox(""",
    """    public static void spawnKingCrimsonAura(LocalPlayer player) {
        if (player == null
                || !(((net.hydra.jojomod.event.powers.StandUser) player).roundabout$getStand()
                instanceof net.hydra.jojomod.entity.stand.KingCrimsonEntity stand)
                || !stand.isAlive() || stand.isRemoved()) {
            return;
        }

        Level level = stand.level();
        RandomSource random = level.random;
        DustParticleOptions crimson = new DustParticleOptions(
                new Vector3f(0.95F, 0.012F, 0.045F), 1.35F
        );
        DustParticleOptions violet = new DustParticleOptions(
                new Vector3f(0.34F, 0.015F, 0.82F), 1.05F
        );

        // A swirling particle cloud around the stand; no line segments or rods.
        double height = Math.max(1.0D, stand.getBbHeight());
        double radiusBase = Math.max(0.48D, stand.getBbWidth() * 0.62D);
        double phase = stand.tickCount * 0.16D;
        for (int i = 0; i < 18; i++) {
            double angle = phase + Math.PI * 2.0D * i / 18.0D;
            double radius = radiusBase + random.nextDouble() * 0.16D;
            double yFraction = ((i % 6) + random.nextDouble() * 0.8D) / 6.0D;
            double x = stand.getX() + Math.cos(angle) * radius;
            double y = stand.getY() + 0.08D + yFraction * height;
            double z = stand.getZ() + Math.sin(angle) * radius;
            double vx = -Math.sin(angle) * 0.014D + (random.nextDouble() - 0.5D) * 0.006D;
            double vy = 0.004D + random.nextDouble() * 0.010D;
            double vz = Math.cos(angle) * 0.014D + (random.nextDouble() - 0.5D) * 0.006D;
            level.addParticle((i % 4 == 0) ? violet : crimson, x, y, z, vx, vy, vz);
        }
    }

    public static void renderPurpleBox(""",
    "King Crimson particle aura helper"
)

# Start the block fracture only once when Time Erase activates; keep the existing aura hook.
replace_once(
    client,
    """                if (PKC.isUsingTimeErase()){
                    if (clientTicker % 2 == 0) {
                        ClientEffectUtil.spawnTerrainFragment(player);
                    }
                    isUsingTimeErase = true;""",
    """                if (clientTicker % 2 == 0) {
                    ClientEffectUtil.spawnKingCrimsonAura(player);
                }
                if (PKC.isUsingTimeErase()){
                    if (!isUsingTimeErase) {
                        ClientEffectUtil.spawnTimeEraseShards(player);
                    }
                    isUsingTimeErase = true;""",
    "one-shot block fracture wave and preserved King Crimson particle aura"
)

# Advance each block through an 8-tick crack phase, a 10-tick flight, and a 6-tick dissolve.
source = effect.read_text(encoding="utf-8")
update_start = "    public static void updateTerrainFragments() {"
update_end = "    /**\n     * Renders wireframe boxes"
a = source.find(update_start)
b = source.find(update_end, a + len(update_start))
if a < 0 or b < 0:
    raise SystemExit("Could not locate updateTerrainFragments method.")

new_update_method = """    public static void updateTerrainFragments() {
        if (!ClientUtil.isUsingTimeErase) {
            terrainFragments.clear();
            return;
        }

        Player player = ClientUtil.getPlayer();
        if (player != null && (((TimeStop) player.level()).inTimeStopRange(player))) {
            return;
        }

        Iterator<TerrainFragments> it = terrainFragments.iterator();
        while (it.hasNext()) {
            TerrainFragments frag = it.next();

            frag.prevPos = frag.pos;
            frag.prevRotX = frag.rotX;
            frag.prevRotY = frag.rotY;
            frag.prevRotZ = frag.rotZ;
            frag.prevScale = frag.scale;

            // Negative age is the per-block propagation delay.
            if (frag.age < 0) {
                frag.age++;
                continue;
            }

            // The shards drift away from the block center for 10 ticks (0.5 seconds).
            if (frag.age >= 8 && frag.age < 18) {
                frag.pos = frag.pos.add(frag.velocity);
            }

            if (frag.age < 18) {
                frag.scale = 1.0F;
            } else {
                frag.scale = Mth.clamp(1.0F - (frag.age - 18) / 6.0F, 0.0F, 1.0F);
            }

            frag.age++;
            if (frag.age >= 24 || frag.scale <= 0.0F) {
                it.remove();
            }
        }
    }

"""
source = source[:a] + new_update_method + source[b:];
effect.write_text(source, encoding="utf-8");

# Replace the original renderer with six true tetrahedral wedges. Exterior faces retain the block
# atlas texture; every interior cut face is full-bright red/purple. Shards open by 0.03-0.08 blocks,
# tilt only 2-5 degrees, then travel for 0.5 seconds without continuous spinning.
# Replace the block-cube renderer with textured tetrahedral shard rendering.
s = renderer.read_text(encoding="utf-8")
s = s.replace(
    "import net.minecraft.client.renderer.texture.OverlayTexture;",
    "import net.minecraft.client.renderer.texture.OverlayTexture;\nimport net.minecraft.client.renderer.texture.TextureAtlas;\nimport net.minecraft.client.renderer.texture.TextureAtlasSprite;"
)
s = s.replace(
    "import org.joml.Matrix4f;",
    "import org.joml.Matrix4f;\nimport org.joml.Matrix3f;"
)
s = s.replace(
    "import java.util.Iterator;",
    "import java.util.Iterator;\nimport java.util.ArrayList;\nimport java.util.Comparator;\nimport java.util.HashMap;\nimport java.util.List;\nimport java.util.Map;\nimport java.util.Random;"
)
start_marker = "    @Unique\n    public void rdbt$renderTerrainFragments("
end_marker = "    @Unique\n    public void rdbt$renderRTZBlockFragments("
start = s.find(start_marker)
end = s.find(end_marker, start + len(start_marker))
if start < 0 or end < 0:
    raise SystemExit("Could not locate terrain fragment renderer methods.")

new_renderer = """    @Unique
    private static final double[][][] rdbt$SHARD_TETRAHEDRA = {
            { {-0.5D,-0.5D,-0.5D}, { 0.5D,-0.5D,-0.5D}, { 0.5D, 0.5D,-0.5D}, { 0.5D, 0.5D, 0.5D} },
            { {-0.5D,-0.5D,-0.5D}, { 0.5D,-0.5D,-0.5D}, { 0.5D,-0.5D, 0.5D}, { 0.5D, 0.5D, 0.5D} },
            { {-0.5D,-0.5D,-0.5D}, {-0.5D, 0.5D,-0.5D}, { 0.5D, 0.5D,-0.5D}, { 0.5D, 0.5D, 0.5D} },
            { {-0.5D,-0.5D,-0.5D}, {-0.5D, 0.5D,-0.5D}, {-0.5D, 0.5D, 0.5D}, { 0.5D, 0.5D, 0.5D} },
            { {-0.5D,-0.5D,-0.5D}, {-0.5D,-0.5D, 0.5D}, { 0.5D,-0.5D, 0.5D}, { 0.5D, 0.5D, 0.5D} },
            { {-0.5D,-0.5D,-0.5D}, {-0.5D,-0.5D, 0.5D}, {-0.5D, 0.5D, 0.5D}, { 0.5D, 0.5D, 0.5D} }
    };

    @Unique
    private static final int[][] rdbt$SHARD_FACES = {
            {0, 1, 2}, {0, 1, 3}, {0, 2, 3}, {1, 2, 3}
    };

    @Unique
    public void rdbt$renderTerrainFragments(
            PoseStack poseStack,
            MultiBufferSource buffer,
            Camera camera,
            float partialTick
    ) {
        if (!ClientUtil.isUsingTimeErase || ClientEffectUtil.terrainFragments.isEmpty()) {
            return;
        }

        Player player = Minecraft.getInstance().player;
        if (player != null && (((TimeStop) player.level()).inTimeStopRange(player))) {
            partialTick = 0.0F;
        }

        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        Vec3 cam = camera.getPosition();
        VertexConsumer shardVertices = buffer.getBuffer(
                RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS)
        );

        for (TerrainFragments frag : ClientEffectUtil.terrainFragments) {
            if (frag.scale <= 0.0F || frag.age >= 24) {
                continue;
            }

            double x = Mth.lerp(partialTick, frag.prevPos.x, frag.pos.x) - cam.x;
            double y = Mth.lerp(partialTick, frag.prevPos.y, frag.pos.y) - cam.y;
            double z = Mth.lerp(partialTick, frag.prevPos.z, frag.pos.z) - cam.z;
            float age = frag.age + partialTick;
            float crackProgress = Mth.clamp(age / 8.0F, 0.0F, 1.0F);
            float flightProgress = Mth.clamp((age - 8.0F) / 10.0F, 0.0F, 1.0F);
            float opacity = Mth.clamp(Mth.lerp(partialTick, frag.prevScale, frag.scale), 0.0F, 1.0F);
            TextureAtlasSprite sprite = dispatcher.getBlockModel(frag.state).getParticleIcon();

            poseStack.pushPose();
            poseStack.translate(x, y, z);

            // Before its wave delay expires, the block remains whole and fully textured.
            if (frag.age < 0) {
                poseStack.pushPose();
                poseStack.translate(-0.5D, -0.5D, -0.5D);
                dispatcher.renderSingleBlock(
                        frag.state, poseStack, buffer,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY
                );
                poseStack.popPose();
                poseStack.popPose();
                continue;
            }

            for (int piece = 0; piece < rdbt$SHARD_TETRAHEDRA.length; piece++) {
                double[][] points = rdbt$SHARD_TETRAHEDRA[piece];
                Vec3 center = new Vec3(
                        (points[0][0] + points[1][0] + points[2][0] + points[3][0]) * 0.25D,
                        (points[0][1] + points[1][1] + points[2][1] + points[3][1]) * 0.25D,
                        (points[0][2] + points[1][2] + points[2][2] + points[3][2]) * 0.25D
                );
                Vec3 direction = center.normalize();
                float massFactor = 0.88F + (piece % 3) * 0.10F;
                double separation = 0.060D * crackProgress + 0.27D * flightProgress * massFactor;
                double rise = 0.035D * flightProgress * (0.85D + (piece % 2) * 0.25D);
                float sign = (piece % 2 == 0) ? 1.0F : -1.0F;
                float tilt = (2.0F + piece * 0.45F) * crackProgress;

                poseStack.pushPose();
                poseStack.translate(
                        direction.x * separation,
                        direction.y * separation + rise,
                        direction.z * separation
                );
                // Only a small fixed 2-5 degree fracture tilt; no continuous shard spinning.
                poseStack.translate(center.x, center.y, center.z);
                poseStack.mulPose(Axis.XP.rotationDegrees(sign * tilt));
                poseStack.mulPose(Axis.ZP.rotationDegrees(-sign * tilt * 0.65F));
                poseStack.translate(-center.x, -center.y, -center.z);

                for (int[] face : rdbt$SHARD_FACES) {
                    Vec3 a = new Vec3(points[face[0]][0], points[face[0]][1], points[face[0]][2]);
                    Vec3 b = new Vec3(points[face[1]][0], points[face[1]][1], points[face[1]][2]);
                    Vec3 c = new Vec3(points[face[2]][0], points[face[2]][1], points[face[2]][2]);
                    Vec3 normal = b.subtract(a).cross(c.subtract(a)).normalize();
                    Vec3 faceCenter = a.add(b).add(c).scale(1.0D / 3.0D);
                    if (normal.dot(faceCenter.subtract(center)) < 0.0D) {
                        Vec3 swap = b;
                        b = c;
                        c = swap;
                        normal = normal.scale(-1.0D);
                    }

                    boolean outside = rdbt$isOuterShardFace(a, b, c);
                    if (outside) {
                        rdbt$renderShardTriangle(
                                shardVertices, poseStack, sprite, a, b, c, normal,
                                true, 1.0F, 1.0F, 1.0F, opacity
                        );
                    } else {
                        float red = (piece % 2 == 0) ? 0.56F : 0.38F;
                        float green = 0.008F;
                        float blue = (piece % 2 == 0) ? 0.11F : 0.24F;
                        rdbt$renderShardTriangle(
                                shardVertices, poseStack, sprite, a, b, c, normal,
                                false, red, green, blue, opacity
                        );
                    }
                }
                poseStack.popPose();
            }
            poseStack.popPose();
        }
    }

    @Unique
    private boolean rdbt$isOuterShardFace(Vec3 a, Vec3 b, Vec3 c) {
        final double epsilon = 0.0001D;
        boolean xFace = Math.abs(a.x - b.x) < epsilon && Math.abs(a.x - c.x) < epsilon
                && Math.abs(Math.abs(a.x) - 0.5D) < epsilon;
        boolean yFace = Math.abs(a.y - b.y) < epsilon && Math.abs(a.y - c.y) < epsilon
                && Math.abs(Math.abs(a.y) - 0.5D) < epsilon;
        boolean zFace = Math.abs(a.z - b.z) < epsilon && Math.abs(a.z - c.z) < epsilon
                && Math.abs(Math.abs(a.z) - 0.5D) < epsilon;
        return xFace || yFace || zFace;
    }

    @Unique
    private void rdbt$renderShardTriangle(
            VertexConsumer out,
            PoseStack poseStack,
            TextureAtlasSprite sprite,
            Vec3 a, Vec3 b, Vec3 c, Vec3 normal,
            boolean outside,
            float red, float green, float blue, float alpha
    ) {
        Matrix4f matrix = poseStack.last().pose();
        Matrix3f normalMatrix = poseStack.last().normal();

        float ua, va, ub, vb, uc, vc;
        if (!outside) {
            // A single atlas sample keeps the newly exposed cut face uniformly tinted.
            ua = ub = uc = sprite.getU(8.0D);
            va = vb = vc = sprite.getV(8.0D);
        } else if (Math.abs(a.x - b.x) < 0.0001D && Math.abs(a.x - c.x) < 0.0001D) {
            ua = sprite.getU((a.y + 0.5D) * 16.0D);
            va = sprite.getV((a.z + 0.5D) * 16.0D);
            ub = sprite.getU((b.y + 0.5D) * 16.0D);
            vb = sprite.getV((b.z + 0.5D) * 16.0D);
            uc = sprite.getU((c.y + 0.5D) * 16.0D);
            vc = sprite.getV((c.z + 0.5D) * 16.0D);
        } else if (Math.abs(a.y - b.y) < 0.0001D && Math.abs(a.y - c.y) < 0.0001D) {
            ua = sprite.getU((a.x + 0.5D) * 16.0D);
            va = sprite.getV((a.z + 0.5D) * 16.0D);
            ub = sprite.getU((b.x + 0.5D) * 16.0D);
            vb = sprite.getV((b.z + 0.5D) * 16.0D);
            uc = sprite.getU((c.x + 0.5D) * 16.0D);
            vc = sprite.getV((c.z + 0.5D) * 16.0D);
        } else {
            ua = sprite.getU((a.x + 0.5D) * 16.0D);
            va = sprite.getV((a.y + 0.5D) * 16.0D);
            ub = sprite.getU((b.x + 0.5D) * 16.0D);
            vb = sprite.getV((b.y + 0.5D) * 16.0D);
            uc = sprite.getU((c.x + 0.5D) * 16.0D);
            vc = sprite.getV((c.y + 0.5D) * 16.0D);
        }

        out.vertex(matrix, (float) a.x, (float) a.y, (float) a.z)
                .color(red, green, blue, alpha).uv(ua, va)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
        out.vertex(matrix, (float) b.x, (float) b.y, (float) b.z)
                .color(red, green, blue, alpha).uv(ub, vb)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
        out.vertex(matrix, (float) c.x, (float) c.y, (float) c.z)
                .color(red, green, blue, alpha).uv(uc, vc)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
        // RenderType.entityTranslucent uses quads; duplicate the final corner to form a triangle.
        out.vertex(matrix, (float) c.x, (float) c.y, (float) c.z)
                .color(red, green, blue, alpha).uv(uc, vc)
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, (float) normal.x, (float) normal.y, (float) normal.z).endVertex();
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

        // Keep the established aura: pulsing red rings and rising energy filaments.
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
            lines.vertex(matrix, x1, 0.12F, z1).color(1.0F, 0.015F, 0.025F, 0.88F).normal(0.0F, 1.0F, 0.0F).endVertex();
            lines.vertex(matrix, x2, 1.78F, z2).color(0.75F, 0.0F, 0.015F, 0.12F).normal(0.0F, 1.0F, 0.0F).endVertex();
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
                    .color(1.0F, 0.015F, 0.025F, 0.78F).normal(0.0F, 1.0F, 0.0F).endVertex();
            lines.vertex(matrix, Mth.cos(a1) * radius, y, Mth.sin(a1) * radius)
                    .color(0.82F, 0.0F, 0.01F, 0.25F).normal(0.0F, 1.0F, 0.0F).endVertex();
        }
    }

"""
s = s[:start] + new_renderer + s[end:]
renderer.write_text(s, encoding="utf-8")

replace_once(
    renderer,
    """        rdbt$renderTerrainFragments(
                $$0,
                buffer,
                $$3,
                $$2
        );

        buffer.endBatch();""",
    """        rdbt$renderTerrainFragments(
                $$0,
                buffer,
                $$3,
                $$2
        );
        rdbt$renderKingCrimsonAura(
                $$0,
                buffer,
                $$3,
                $$2
        );
        buffer.endBatch();
        RenderSystem.lineWidth(1.0F);""",
    "render tetrahedral Time Erase shards and preserve the King Crimson aura hook"
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

print("Applied: clean Time Erase terrain vanish with no cracks or debris, plus a rendered red King Crimson aura.")
