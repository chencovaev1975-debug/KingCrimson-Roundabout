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

        // Each exposed block becomes one fragment group. The renderer splits
        // the group into four large textured chunks after the crack phase.
        terrainFragments.clear();
        for (BlockPos pos : surroundingBlocks) {
            BlockState state = level.getBlockState(pos);
            Vec3 blockCenter = Vec3.atCenterOf(pos);

            TerrainFragments fragment = new TerrainFragments();
            fragment.state = state;
            fragment.pos = blockCenter;
            fragment.prevPos = blockCenter;

            Vec3 outward = blockCenter.subtract(player.position());
            if (outward.lengthSqr() < 1.0E-5D) {
                outward = new Vec3(
                        random.nextGaussian(),
                        random.nextGaussian() * 0.5D,
                        random.nextGaussian()
                );
            }
            outward = outward.normalize();
            // Fragments stay still while the fissure opens, then make a short,
            // direct upward/outward flight. They do not spin.
            fragment.velocity = new Vec3(outward.x * 0.012D, 0.036D, outward.z * 0.012D);

            fragment.rotX = random.nextFloat() * 360.0F;
            fragment.rotY = random.nextFloat() * 360.0F;
            fragment.rotZ = random.nextFloat() * 360.0F;
            fragment.prevRotX = fragment.rotX;
            fragment.prevRotY = fragment.rotY;
            fragment.prevRotZ = fragment.rotZ;
            fragment.rotSpeedX = 0.0F;
            fragment.rotSpeedY = 0.0F;
            fragment.rotSpeedZ = 0.0F;
            fragment.prevScale = 1.0F;
            fragment.scale = 1.0F;
            fragment.age = 0;
            fragment.maxAge = 32; // 16 crack ticks + 10 flight ticks + 6 dissolve ticks
            terrainFragments.add(fragment);
        }
    }

"""
source = source[:start] + new_effect_method + source[end:]
effect.write_text(source, encoding="utf-8")

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
        if (player == null) {
            return;
        }

        Level level = player.level();
        RandomSource random = level.random;
        DustParticleOptions aura = new DustParticleOptions(
                new Vector3f(0.82F, 0.012F, 0.028F),
                1.25F
        );

        // A loose, continuously replenished particle volume around the player's body.
        for (int i = 0; i < 10; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double radius = 0.36D + random.nextDouble() * 0.24D;
            double x = player.getX() + Math.cos(angle) * radius;
            double y = player.getY() + 0.08D + random.nextDouble() * Math.max(0.8D, player.getBbHeight());
            double z = player.getZ() + Math.sin(angle) * radius;
            double vx = (random.nextDouble() - 0.5D) * 0.008D;
            double vy = 0.006D + random.nextDouble() * 0.012D;
            double vz = (random.nextDouble() - 0.5D) * 0.008D;
            level.addParticle(aura, x, y, z, vx, vy, vz);
        }
    }

    public static void renderPurpleBox(""",
    "King Crimson particle aura helper"
)

# Fire the debris burst once on the transition into Time Erase.
replace_once(
    client,
    """                if (PKC.isUsingTimeErase()){
                    if (clientTicker % 2 == 0) {
                        ClientEffectUtil.spawnTerrainFragment(player);
                    }
                    isUsingTimeErase = true;""",
    """                if (clientTicker % 2 == 0 && ((StandUser) player).roundabout$getStand() != null
                        && ((StandUser) player).roundabout$getStand().isAlive()) {
                    ClientEffectUtil.spawnKingCrimsonAura(player);
                }
                if (PKC.isUsingTimeErase()){
                    if (!isUsingTimeErase) {
                        ClientEffectUtil.spawnTimeEraseShards(player);
                    }
                    isUsingTimeErase = true;""",
    "one-shot 360-degree Time Erase shard trigger"
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
    """            if (frag.age >= 16 && frag.age < 26) {
                // Ten ticks = 0.5 seconds at Minecraft's 20 ticks per second.
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
    """            if (ClientUtil.isUsingTimeErase && frag.age < 26) {
                frag.scale = 1.0F;
            } else {
                // Six ticks of smooth transparency fade after the short flight.
                frag.scale = Math.max(0.0F, frag.scale - 0.17F);
            }""",
    "gradual shard evaporation"
)

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
            if (frag.scale <= 0.0F) {
                it.remove();
                continue;
            }

            double x = Mth.lerp(partialTick, frag.prevPos.x, frag.pos.x) - cam.x;
            double y = Mth.lerp(partialTick, frag.prevPos.y, frag.pos.y) - cam.y;
            double z = Mth.lerp(partialTick, frag.prevPos.z, frag.pos.z) - cam.z;
            float crackRotation = Mth.lerp(partialTick, frag.prevRotZ, frag.rotZ);
            float opacity = Mth.lerp(partialTick, frag.prevScale, frag.scale);

            poseStack.pushPose();
            poseStack.translate(x, y, z);

            if (frag.age < 16) {
                // The intact block is still present; the fissures are recessed bevel geometry,
                // not black line segments painted across the surface.
                poseStack.pushPose();
                poseStack.translate(-0.5D, -0.5D, -0.5D);
                dispatcher.renderSingleBlock(
                        frag.state, poseStack, buffer,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY
                );
                poseStack.popPose();

                poseStack.pushPose();
                poseStack.mulPose(Axis.YP.rotationDegrees(crackRotation));
                rdbt$renderCrackLines(poseStack, buffer, frag.age);
                poseStack.popPose();
            } else {
                // Four substantial angular shards detach from the block.
                // They translate outward and upward for 10 ticks; their orientation never changes.
                float flight = Mth.clamp((frag.age - 16 + partialTick) / 10.0F, 0.0F, 1.0F);
                float separation = 0.30F * flight;
                float extraRise = 0.07F * flight;

                VertexConsumer shards = buffer.getBuffer(RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS));
                TextureAtlasSprite sprite = dispatcher.getBlockModel(frag.state).getParticleIcon();

                for (int piece = 0; piece < 4; piece++) {
                    float sx = (piece == 0 || piece == 3) ? -1.0F : 1.0F;
                    float sz = (piece < 2) ? -1.0F : 1.0F;
                    float angle = (float) (Math.PI * 0.5D * piece) + crackRotation * ((float) Math.PI / 180.0F);
                    float dx = Mth.cos(angle) * separation;
                    float dz = Mth.sin(angle) * separation;
                    float dy = extraRise + (piece % 2) * 0.025F * flight;

                    poseStack.pushPose();
                    poseStack.translate(dx, dy, dz);

                    // A broad wedge cut from one corner of the original block.
                    // Two outside faces keep the block texture; the two fresh fracture faces are darkened.
                    Vec3 a = new Vec3(sx * 0.48D,  0.46D, sz * 0.48D);
                    Vec3 b = new Vec3(sx * 0.48D, -0.46D, sz * 0.48D);
                    Vec3 c = new Vec3(sx * 0.48D, -0.46D, sz * 0.06D);
                    Vec3 d = new Vec3(sx * 0.06D, -0.46D, sz * 0.48D);
                    rdbt$renderBlockShard(shards, poseStack, sprite, a, b, c, d, opacity);
                    poseStack.popPose();
                }
            }

            poseStack.popPose();
        }
    }

    @Unique
    private void rdbt$renderCrackLines(PoseStack poseStack, MultiBufferSource buffer, int age) {
        VertexConsumer grooves = buffer.getBuffer(RenderType.debugQuads());
        Matrix4f matrix = poseStack.last().pose();
        float spread = Math.min(1.0F, 0.34F + age * 0.042F);
        float lift = 0.503F;

        // Jagged main fissure and irregular branches across the top face.
        rdbt$crackSegment(grooves, matrix, -0.46F * spread, lift, -0.34F * spread, -0.29F * spread, lift, -0.17F * spread);
        rdbt$crackSegment(grooves, matrix, -0.29F * spread, lift, -0.17F * spread, -0.13F * spread, lift, -0.025F * spread);
        rdbt$crackSegment(grooves, matrix, -0.13F * spread, lift, -0.025F * spread, 0.035F * spread, lift, 0.105F * spread);
        rdbt$crackSegment(grooves, matrix, 0.035F * spread, lift, 0.105F * spread, 0.19F * spread, lift, 0.23F * spread);
        rdbt$crackSegment(grooves, matrix, 0.19F * spread, lift, 0.23F * spread, 0.45F * spread, lift, 0.39F * spread);

        // Branches make the fracture read as a crack network rather than parallel stripes.
        rdbt$crackSegment(grooves, matrix, -0.29F * spread, lift, -0.17F * spread, -0.43F * spread, lift, -0.015F * spread);
        rdbt$crackSegment(grooves, matrix, -0.43F * spread, lift, -0.015F * spread, -0.48F * spread, lift, 0.16F * spread);
        rdbt$crackSegment(grooves, matrix, -0.13F * spread, lift, -0.025F * spread, -0.31F * spread, lift, 0.15F * spread);
        rdbt$crackSegment(grooves, matrix, -0.31F * spread, lift, 0.15F * spread, -0.34F * spread, lift, 0.36F * spread);
        rdbt$crackSegment(grooves, matrix, 0.035F * spread, lift, 0.105F * spread, -0.015F * spread, lift, 0.30F * spread);
        rdbt$crackSegment(grooves, matrix, -0.015F * spread, lift, 0.30F * spread, 0.08F * spread, lift, 0.46F * spread);
        rdbt$crackSegment(grooves, matrix, 0.19F * spread, lift, 0.23F * spread, 0.34F * spread, lift, 0.075F * spread);
        rdbt$crackSegment(grooves, matrix, 0.34F * spread, lift, 0.075F * spread, 0.48F * spread, lift, 0.005F * spread);
        rdbt$crackSegment(grooves, matrix, 0.19F * spread, lift, 0.23F * spread, 0.30F * spread, lift, 0.42F * spread);

        // Continue the fissures across two visible side faces.
        rdbt$crackSegment(grooves, matrix, -0.40F * spread, -0.34F * spread, 0.503F, -0.20F * spread, -0.18F * spread, 0.503F);
        rdbt$crackSegment(grooves, matrix, -0.20F * spread, -0.18F * spread, 0.503F, 0.02F * spread, 0.00F * spread, 0.503F);
        rdbt$crackSegment(grooves, matrix, 0.02F * spread, 0.00F * spread, 0.503F, 0.23F * spread, 0.22F * spread, 0.503F);
        rdbt$crackSegment(grooves, matrix, 0.02F * spread, 0.00F * spread, 0.503F, -0.16F * spread, 0.23F * spread, 0.503F);
        rdbt$crackSegment(grooves, matrix, 0.23F * spread, 0.22F * spread, 0.503F, 0.42F * spread, 0.36F * spread, 0.503F);

        rdbt$crackSegment(grooves, matrix, 0.503F, -0.36F * spread, -0.34F * spread, 0.503F, -0.18F * spread, -0.16F * spread);
        rdbt$crackSegment(grooves, matrix, 0.503F, -0.18F * spread, -0.16F * spread, 0.503F, 0.02F * spread, 0.035F * spread);
        rdbt$crackSegment(grooves, matrix, 0.503F, 0.02F * spread, 0.035F * spread, 0.503F, 0.24F * spread, 0.28F * spread);
        rdbt$crackSegment(grooves, matrix, 0.503F, 0.02F * spread, 0.035F * spread, 0.503F, 0.27F * spread, -0.13F * spread);
    }

    @Unique
    private void rdbt$crackSegment(VertexConsumer out, Matrix4f matrix,
                                   float ax, float ay, float az,
                                   float bx, float by, float bz) {
        // Work out the face normal and the perpendicular direction in that face.
        float nx = 0.0F, ny = 0.0F, nz = 0.0F;
        float px, py, pz;
        if (Math.abs(ay - by) < 0.0001F) {
            ny = ay >= 0.0F ? 1.0F : -1.0F;
            px = -(bz - az);
            py = 0.0F;
            pz = bx - ax;
        } else if (Math.abs(az - bz) < 0.0001F) {
            nz = az >= 0.0F ? 1.0F : -1.0F;
            px = -(by - ay);
            py = bx - ax;
            pz = 0.0F;
        } else {
            nx = ax >= 0.0F ? 1.0F : -1.0F;
            px = 0.0F;
            py = -(bz - az);
            pz = by - ay;
        }

        float perpendicularLength = Mth.sqrt(px * px + py * py + pz * pz);
        if (perpendicularLength < 0.0001F) {
            return;
        }
        px /= perpendicularLength;
        py /= perpendicularLength;
        pz /= perpendicularLength;

        float halfWidth = 0.026F + Math.min(0.010F, perpendicularLength * 0.012F);
        float floorHalfWidth = 0.006F;
        float lipHeight = 0.0060F;
        float floorHeight = 0.0012F;

        float lAx = ax + px * halfWidth + nx * lipHeight;
        float lAy = ay + py * halfWidth + ny * lipHeight;
        float lAz = az + pz * halfWidth + nz * lipHeight;
        float lBx = bx + px * halfWidth + nx * lipHeight;
        float lBy = by + py * halfWidth + ny * lipHeight;
        float lBz = bz + pz * halfWidth + nz * lipHeight;

        float iLAx = ax + px * floorHalfWidth + nx * floorHeight;
        float iLAy = ay + py * floorHalfWidth + ny * floorHeight;
        float iLAz = az + pz * floorHalfWidth + nz * floorHeight;
        float iLBx = bx + px * floorHalfWidth + nx * floorHeight;
        float iLBy = by + py * floorHalfWidth + ny * floorHeight;
        float iLBz = bz + pz * floorHalfWidth + nz * floorHeight;

        float iRAx = ax - px * floorHalfWidth + nx * floorHeight;
        float iRAy = ay - py * floorHalfWidth + ny * floorHeight;
        float iRAz = az - pz * floorHalfWidth + nz * floorHeight;
        float iRBx = bx - px * floorHalfWidth + nx * floorHeight;
        float iRBy = by - py * floorHalfWidth + ny * floorHeight;
        float iRBz = bz - pz * floorHalfWidth + nz * floorHeight;

        float rAx = ax - px * halfWidth + nx * lipHeight;
        float rAy = ay - py * halfWidth + ny * lipHeight;
        float rAz = az - pz * halfWidth + nz * lipHeight;
        float rBx = bx - px * halfWidth + nx * lipHeight;
        float rBy = by - py * halfWidth + ny * lipHeight;
        float rBz = bz - pz * halfWidth + nz * lipHeight;

        // Light-facing bevel, nearly-black recessed floor, and darker opposite wall.
        rdbt$crackQuad(out, matrix,
                lAx,lAy,lAz, lBx,lBy,lBz, iLBx,iLBy,iLBz, iLAx,iLAy,iLAz,
                0.19F, 0.17F, 0.145F);
        rdbt$crackQuad(out, matrix,
                iLAx,iLAy,iLAz, iLBx,iLBy,iLBz, iRBx,iRBy,iRBz, iRAx,iRAy,iRAz,
                0.003F, 0.0025F, 0.002F);
        rdbt$crackQuad(out, matrix,
                iRAx,iRAy,iRAz, iRBx,iRBy,iRBz, rBx,rBy,rBz, rAx,rAy,rAz,
                0.028F, 0.024F, 0.020F);
    }

    @Unique
    private void rdbt$crackQuad(VertexConsumer out, Matrix4f matrix,
                                float x1,float y1,float z1,
                                float x2,float y2,float z2,
                                float x3,float y3,float z3,
                                float x4,float y4,float z4,
                                float red,float green,float blue) {
        out.vertex(matrix, x1, y1, z1).color(red, green, blue, 1.0F).endVertex();
        out.vertex(matrix, x2, y2, z2).color(red, green, blue, 1.0F).endVertex();
        out.vertex(matrix, x3, y3, z3).color(red, green, blue, 1.0F).endVertex();
        out.vertex(matrix, x4, y4, z4).color(red, green, blue, 1.0F).endVertex();
    }

    @Unique
    private void rdbt$renderBlockShard(VertexConsumer out, PoseStack poseStack,
                                       TextureAtlasSprite sprite, Vec3 a, Vec3 b, Vec3 c, Vec3 d,
                                       float opacity) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f matrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();

        // Original outside faces stay textured; the freshly broken faces are dark stone.
        rdbt$shardTriangle(out, matrix, normalMatrix, sprite, a, b, c, 1.0F, 1.0F, 1.0F, opacity);
        rdbt$shardTriangle(out, matrix, normalMatrix, sprite, a, b, d, 1.0F, 1.0F, 1.0F, opacity);
        rdbt$shardTriangle(out, matrix, normalMatrix, sprite, a, c, d, 0.22F, 0.20F, 0.18F, opacity);
        rdbt$shardTriangle(out, matrix, normalMatrix, sprite, b, d, c, 0.15F, 0.13F, 0.12F, opacity);
    }

    @Unique
    private void rdbt$shardTriangle(VertexConsumer out, Matrix4f matrix, Matrix3f normalMatrix,
                                    TextureAtlasSprite sprite, Vec3 a, Vec3 b, Vec3 c,
                                    float red, float green, float blue, float alpha) {
        Vec3 faceNormal = b.subtract(a).cross(c.subtract(a)).normalize();
        float u0 = sprite.getU(0.1D);
        float u1 = sprite.getU(15.9D);
        float v0 = sprite.getV(0.1D);
        float v1 = sprite.getV(15.9D);

        rdbt$shardVertex(out, matrix, normalMatrix, a, u0, v0, red, green, blue, alpha, faceNormal);
        rdbt$shardVertex(out, matrix, normalMatrix, b, u1, v0, red, green, blue, alpha, faceNormal);
        rdbt$shardVertex(out, matrix, normalMatrix, c, u1, v1, red, green, blue, alpha, faceNormal);
    }

    @Unique
    private void rdbt$shardVertex(VertexConsumer out, Matrix4f matrix, Matrix3f normalMatrix,
                                  Vec3 point, float u, float v,
                                  float red, float green, float blue, float alpha, Vec3 normal) {
        out.vertex(matrix, (float) point.x, (float) point.y, (float) point.z)
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, (float) normal.x, (float) normal.y, (float) normal.z)
                .endVertex();
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

print("Applied: 360-degree nearby block cracks, four large textured debris chunks per block, slow upward motion, gradual shrink-out, and immediate terrain restoration.")
