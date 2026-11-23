package net.hydra.jojomod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Client-only Time Erase fracture pass.
 * It snapshots nearby exposed block states without changing the world, then renders
 * those blocks, their cracks, and small non-rotating stone chips over the native void.
 */
public final class TimeEraseFractureEffect {
    private static final int RADIUS = 60;
    private static final int MAX_BLOCKS = 12000;
    private static final int BAND_COUNT = 6;
    private static final int BAND_RADIUS = RADIUS / BAND_COUNT;
    // Each ring has enough capacity to retain every terrain column (no sparse sampling).
    private static final int[] BAND_CAPS = {320, 950, 1580, 2220, 2840, 3460};
    private static final int CRACKS_REACH_TICK = 36;
    private static final int SHATTER_TICK = 46; // 2.30 seconds at 20 TPS
    private static final int CAMERA_SHAKE_END_TICK = 60;
    private static final int SHARD_HOVER_TICKS = 12;
    private static final int SHARD_FADE_TICKS = 20;
    private static final int FINISH_TICK = 78;  // 3.90 seconds; gives detached chips time to hover and fade

    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DY = {1, -1, 0, 0, 0, 0};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    // A face-local basis for UP, DOWN, NORTH, SOUTH, WEST, EAST.
    private static final float[][] NORMAL = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, -1},
            {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };
    private static final float[][] U_AXIS = {
            {1, 0, 0}, {1, 0, 0}, {1, 0, 0},
            {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final float[][] V_AXIS = {
            {0, 0, -1}, {0, 0, 1}, {0, 1, 0},
            {0, 1, 0}, {0, 1, 0}, {0, 1, 0}
    };

    private static final float[][] FACE_ORIGIN = {
            {0.5F, 1.0F, 0.5F}, {0.5F, 0.0F, 0.5F},
            {0.5F, 0.5F, 0.0F}, {0.5F, 0.5F, 1.0F},
            {0.0F, 0.5F, 0.5F}, {1.0F, 0.5F, 0.5F}
    };

    private static final List<BlockRecord> BLOCKS = new ArrayList<>();
    private static boolean previousAbilityActive;
    private static boolean effectActive;
    private static int elapsedTicks;
    private static ClientLevel capturedLevel;
    private static double centerX;
    private static double centerY;
    private static double centerZ;
    private static float appliedCameraShake;

    private TimeEraseFractureEffect() {}

    public static void tick(LocalPlayer player, boolean abilityActive) {
        if (player == null) {
            reset();
            return;
        }

        ClientLevel level = (ClientLevel) player.level();
        if (capturedLevel != null && capturedLevel != level) {
            reset();
        }

        if (abilityActive && !previousAbilityActive) {
            begin(player, level);
        } else if (effectActive) {
            elapsedTicks++;
            if (elapsedTicks < BAND_COUNT && capturedLevel != null) {
                captureBand(capturedLevel,
                        (int) Math.floor(centerX), (int) Math.floor(centerY),
                        (int) Math.floor(centerZ), elapsedTicks);
            }
            if (elapsedTicks >= FINISH_TICK) {
                effectActive = false;
                BLOCKS.clear();
            }
        }

        previousAbilityActive = abilityActive;
    }

    private static void begin(LocalPlayer player, ClientLevel level) {
        BLOCKS.clear();
        capturedLevel = level;
        centerX = player.getX();
        centerY = player.getY();
        centerZ = player.getZ();
        elapsedTicks = 0;
        effectActive = true;
        appliedCameraShake = 0.0F;
        captureBand(level, (int) Math.floor(centerX), (int) Math.floor(centerY),
                (int) Math.floor(centerZ), 0);
    }

    public static void reset() {
        BLOCKS.clear();
        capturedLevel = null;
        previousAbilityActive = false;
        effectActive = false;
        elapsedTicks = 0;
        appliedCameraShake = 0.0F;
    }

    private static void captureBand(ClientLevel level, int cx, int cy, int cz, int bandIndex) {
        if (bandIndex < 0 || bandIndex >= BAND_COUNT) return;

        int outerRadius = Math.min(RADIUS, (bandIndex + 1) * BAND_RADIUS);
        int innerRadius = bandIndex * BAND_RADIUS;
        int outerSquared = outerRadius * outerRadius;
        int innerSquared = innerRadius * innerRadius;
        int capacity = BAND_CAPS[bandIndex];
        Random random = new Random((((long) cx) << 32) ^ (((long) cz) << 1)
                ^ cy ^ (0x4B435246L + bandIndex * 0x9E3779B9L));
        List<BlockSnapshot> reservoir = new ArrayList<>(capacity);
        int seen = 0;

        // Every X/Z column contributes only its highest terrain block. Tree trunks and
        // leaves are ignored, so the temporary scene contains the ground surface only.
        for (int x = cx - outerRadius; x <= cx + outerRadius; x++) {
            int dx = x - cx;
            for (int z = cz - outerRadius; z <= cz + outerRadius; z++) {
                int dz = z - cz;
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > outerSquared
                        || (bandIndex > 0 && distanceSquared <= innerSquared)) continue;

                int surfaceY = findTerrainSurfaceY(level, x, z);
                if (surfaceY == Integer.MIN_VALUE) continue;

                BlockPos pos = new BlockPos(x, surfaceY, z);
                BlockState state = level.getBlockState(pos);
                int faceMask = 1; // UP: the top surface is always exposed.

                // Add only side walls where the neighbouring terrain column is lower.
                // There is deliberately no DOWN face: underground blocks never enter
                // the snapshot and the underside of the surface is not fractured.
                if (findTerrainSurfaceY(level, x, z - 1) < surfaceY) faceMask |= 1 << 2;
                if (findTerrainSurfaceY(level, x, z + 1) < surfaceY) faceMask |= 1 << 3;
                if (findTerrainSurfaceY(level, x - 1, z) < surfaceY) faceMask |= 1 << 4;
                if (findTerrainSurfaceY(level, x + 1, z) < surfaceY) faceMask |= 1 << 5;

                seen++;
                BlockSnapshot snapshot = new BlockSnapshot(x, surfaceY, z, state, faceMask);
                if (reservoir.size() < capacity) {
                    reservoir.add(snapshot);
                } else {
                    int replace = random.nextInt(seen);
                    if (replace < capacity) reservoir.set(replace, snapshot);
                }
            }
        }

        for (BlockSnapshot snapshot : reservoir) {
            BLOCKS.add(new BlockRecord(level, snapshot, cx, cz));
        }
    }

    private static int findTerrainSurfaceY(ClientLevel level, int x, int z) {
        int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        for (int y = topY; y >= level.getMinBuildHeight(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()
                    || !state.getFluidState().isEmpty()
                    || state.is(BlockTags.LEAVES)
                    || state.is(BlockTags.LOGS)
                    || !state.isSolidRender(level, pos)) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }
    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffer,
                              Camera camera, float partialTick) {
        if (!effectActive || BLOCKS.isEmpty() || !ClientUtil.renderTimeErase()) return;

        View view = new View(camera);

        if (elapsedTicks < SHATTER_TICK) {
            renderIntactBlocks(poseStack, buffer, view);
            buffer.endBatch();
            renderCracks(poseStack, view, partialTick);
        } else {
            renderShards(poseStack, view, partialTick);
        }
    }

    private static void renderIntactBlocks(PoseStack poseStack, MultiBufferSource buffer,
                                           View camera) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        for (BlockRecord record : BLOCKS) {
            if (!isInView(record, camera)) continue;
            poseStack.pushPose();
            poseStack.translate(
                    record.x - camera.x,
                    record.y - camera.y,
                    record.z - camera.z
            );
            dispatcher.renderSingleBlock(
                    record.state,
                    poseStack,
                    buffer,
                    record.packedLight,
                    OverlayTexture.NO_OVERLAY
            );
            poseStack.popPose();
        }
    }

    private static void renderCracks(PoseStack poseStack, View camera,
                                     float partialTick) {
        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = poseStack.last().pose();
        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder builder = tessellator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        float now = elapsedTicks + partialTick;
        for (BlockRecord record : BLOCKS) {
            if (!isInView(record, camera)) continue;
            float reveal = now - record.crackStartTick;
            if (reveal <= 0.0F) continue;
            float alpha = Mth.clamp(reveal / 2.5F, 0.0F, 1.0F) * 0.98F;
            float[] segments = record.crackSegments;

            // Eight values per segment: face index, two local-space endpoints, half-width.
            for (int i = 0; i + 7 < segments.length; i += 8) {
                int face = (int) segments[i];
                float x0 = record.x + segments[i + 1];
                float y0 = record.y + segments[i + 2];
                float z0 = record.z + segments[i + 3];
                float x1 = record.x + segments[i + 4];
                float y1 = record.y + segments[i + 5];
                float z1 = record.z + segments[i + 6];
                float halfWidth = segments[i + 7];
                emitCrackRibbon(builder, matrix, x0, y0, z0, x1, y1, z1,
                        NORMAL[face][0], NORMAL[face][1], NORMAL[face][2],
                        halfWidth, alpha);
            }
        }

        BufferUploader.drawWithShader(builder.end());
        poseStack.popPose();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    private static void emitCrackRibbon(BufferBuilder builder, Matrix4f matrix,
                                        float x0, float y0, float z0,
                                        float x1, float y1, float z1,
                                        float nx, float ny, float nz,
                                        float halfWidth, float alpha) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;

        // Perpendicular direction stays in the block face plane.
        float px = ny * dz - nz * dy;
        float py = nz * dx - nx * dz;
        float pz = nx * dy - ny * dx;
        float length = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (length < 1.0E-5F) return;
        px /= length;
        py /= length;
        pz /= length;

        // A dark recessed outer groove, a near-black core, and narrow raised lips
        // create visible width and depth from real generated geometry (not a texture).
        emitCrackLayer(builder, matrix, x0, y0, z0, x1, y1, z1,
                nx, ny, nz, px, py, pz, halfWidth * 1.34F, 0.008F,
                0.030F, 0.032F, 0.035F, alpha);
        emitCrackLayer(builder, matrix, x0, y0, z0, x1, y1, z1,
                nx, ny, nz, px, py, pz, halfWidth * 0.78F, 0.012F,
                0.004F, 0.005F, 0.006F, alpha);
        emitCrackLayer(builder, matrix, x0, y0, z0, x1, y1, z1,
                nx, ny, nz, px, py, pz, halfWidth * 0.10F, 0.014F,
                0.135F, 0.140F, 0.145F, alpha,
                px * halfWidth * 0.91F, py * halfWidth * 0.91F, pz * halfWidth * 0.91F);
        emitCrackLayer(builder, matrix, x0, y0, z0, x1, y1, z1,
                nx, ny, nz, px, py, pz, halfWidth * 0.08F, 0.010F,
                0.080F, 0.085F, 0.090F, alpha,
                -px * halfWidth * 0.91F, -py * halfWidth * 0.91F, -pz * halfWidth * 0.91F);
    }

    private static void emitCrackLayer(BufferBuilder builder, Matrix4f matrix,
                                       float x0, float y0, float z0,
                                       float x1, float y1, float z1,
                                       float nx, float ny, float nz,
                                       float px, float py, float pz,
                                       float halfWidth, float depth,
                                       float r, float g, float b, float alpha) {
        emitCrackLayer(builder, matrix, x0, y0, z0, x1, y1, z1,
                nx, ny, nz, px, py, pz, halfWidth, depth, r, g, b, alpha, 0, 0, 0);
    }

    private static void emitCrackLayer(BufferBuilder builder, Matrix4f matrix,
                                       float x0, float y0, float z0,
                                       float x1, float y1, float z1,
                                       float nx, float ny, float nz,
                                       float px, float py, float pz,
                                       float halfWidth, float depth,
                                       float r, float g, float b, float alpha,
                                       float shiftX, float shiftY, float shiftZ) {
        float ox = nx * depth + shiftX;
        float oy = ny * depth + shiftY;
        float oz = nz * depth + shiftZ;
        float wx = px * halfWidth;
        float wy = py * halfWidth;
        float wz = pz * halfWidth;

        vertex(builder, matrix, x0 + ox + wx, y0 + oy + wy, z0 + oz + wz, r, g, b, alpha);
        vertex(builder, matrix, x1 + ox + wx, y1 + oy + wy, z1 + oz + wz, r, g, b, alpha);
        vertex(builder, matrix, x1 + ox - wx, y1 + oy - wy, z1 + oz - wz, r, g, b, alpha);
        vertex(builder, matrix, x0 + ox - wx, y0 + oy - wy, z0 + oz - wz, r, g, b, alpha);
    }
    private static void renderShards(PoseStack poseStack, View camera,
                                     float partialTick) {
        float now = elapsedTicks + partialTick;
        float sinceSeparation = Math.max(0.0F, now - SHATTER_TICK);
        float drift = Mth.clamp(sinceSeparation / 12.0F, 0.0F, 1.0F);
        float fadeProgress = Mth.clamp(
                (sinceSeparation - SHARD_HOVER_TICKS) / (float) SHARD_FADE_TICKS,
                0.0F, 1.0F
        );
        float easedFade = fadeProgress * fadeProgress * (3.0F - 2.0F * fadeProgress);
        float alpha = 1.0F - easedFade;
        if (alpha <= 0.001F) return;

        RenderSystem.enableDepthTest();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = poseStack.last().pose();
        Tesselator tessellator = Tesselator.getInstance();
        BufferBuilder builder = tessellator.getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (BlockRecord record : BLOCKS) {
            if (!isInView(record, camera)) continue;
            for (int shardIndex = 0; shardIndex < record.shards.length; shardIndex++) {
                Shard shard = record.shards[shardIndex];
                if (!isFaceTowardCamera(record, shard, camera)) continue;

                // The first frame is already clearly detached. Later motion is a
                // restrained upward drift, not an explosive scatter or rotation.
                float phase = record.x * 0.17F + record.y * 0.31F
                        + record.z * 0.11F + shardIndex * 1.37F;
                float hover = (float) Math.sin(sinceSeparation * 0.34F + phase) * 0.018F;
                float tx = shard.normalX * 0.24F + shard.velocityX * drift;
                float ty = shard.normalY * 0.24F + 0.12F
                        + shard.velocityY * drift + hover;
                float tz = shard.normalZ * 0.24F + shard.velocityZ * drift;
                emitShard(builder, matrix, record, shard, tx, ty, tz,
                        1.0F, 1.0F, alpha);
            }
        }

        BufferUploader.drawWithShader(builder.end());
        poseStack.popPose();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }
    private static void emitShard(BufferBuilder builder, Matrix4f matrix, BlockRecord record,
                                  Shard shard, float tx, float ty, float tz,
                                  float scale, float surfaceGrowth, float alpha) {
        int n = shard.vertexCount;
        float[] vertices = shard.vertices;
        float cx = record.x + 0.5F + tx;
        float cy = record.y + 0.5F + ty;
        float cz = record.z + 0.5F + tz;

        float[] back = new float[n * 3];
        float[] front = new float[n * 3];
        for (int i = 0; i < n; i++) {
            int base = i * 6;
            float backX = vertices[base] - 0.5F;
            float backY = vertices[base + 1] - 0.5F;
            float backZ = vertices[base + 2] - 0.5F;
            float frontX = vertices[base + 3] - 0.5F;
            float frontY = vertices[base + 4] - 0.5F;
            float frontZ = vertices[base + 5] - 0.5F;
            if (surfaceGrowth < 1.0F) {
                float backDepth = backX * shard.normalX + backY * shard.normalY
                        + backZ * shard.normalZ - 0.5F;
                float frontDepth = frontX * shard.normalX + frontY * shard.normalY
                        + frontZ * shard.normalZ - 0.5F;
                if (backDepth > 0.0F) {
                    float correction = backDepth * (surfaceGrowth - 1.0F);
                    backX += shard.normalX * correction;
                    backY += shard.normalY * correction;
                    backZ += shard.normalZ * correction;
                }
                if (frontDepth > 0.0F) {
                    float correction = frontDepth * (surfaceGrowth - 1.0F);
                    frontX += shard.normalX * correction;
                    frontY += shard.normalY * correction;
                    frontZ += shard.normalZ * correction;
                }
            }
            back[i * 3] = cx + backX * scale;
            back[i * 3 + 1] = cy + backY * scale;
            back[i * 3 + 2] = cz + backZ * scale;
            front[i * 3] = cx + frontX * scale;
            front[i * 3 + 1] = cy + frontY * scale;
            front[i * 3 + 2] = cz + frontZ * scale;
        }

        float centerBackX = 0.0F, centerBackY = 0.0F, centerBackZ = 0.0F;
        float centerFrontX = 0.0F, centerFrontY = 0.0F, centerFrontZ = 0.0F;
        for (int i = 0; i < n; i++) {
            centerBackX += back[i * 3] - cx;
            centerBackY += back[i * 3 + 1] - cy;
            centerBackZ += back[i * 3 + 2] - cz;
            centerFrontX += front[i * 3] - cx;
            centerFrontY += front[i * 3 + 1] - cy;
            centerFrontZ += front[i * 3 + 2] - cz;
        }
        centerBackX /= n; centerBackY /= n; centerBackZ /= n;
        centerFrontX /= n; centerFrontY /= n; centerFrontZ /= n;

        float dark = shard.darkness;
        for (int i = 0; i < n; i++) {
            int next = (i + 1) % n;
            int bi = i * 3;
            int bn = next * 3;
            float sideShade = dark * (0.66F + ((i % 3) * 0.11F));
            quad(builder, matrix,
                    back[bi], back[bi + 1], back[bi + 2],
                    back[bn], back[bn + 1], back[bn + 2],
                    front[bn], front[bn + 1], front[bn + 2],
                    front[bi], front[bi + 1], front[bi + 2],
                    sideShade, sideShade + 0.012F, sideShade + 0.018F, alpha);

            // The two caps are triangulated into degenerate quads to retain the
            // existing POSITION_COLOR/QUADS pipeline without adding render types.
            quad(builder, matrix,
                    cx + centerBackX * scale, cy + centerBackY * scale, cz + centerBackZ * scale,
                    back[bi], back[bi + 1], back[bi + 2],
                    back[bn], back[bn + 1], back[bn + 2],
                    back[bn], back[bn + 1], back[bn + 2],
                    dark * 0.52F, dark * 0.54F, dark * 0.56F, alpha);
            quad(builder, matrix,
                    cx + centerFrontX * scale, cy + centerFrontY * scale, cz + centerFrontZ * scale,
                    front[bn], front[bn + 1], front[bn + 2],
                    front[bi], front[bi + 1], front[bi + 2],
                    front[bi], front[bi + 1], front[bi + 2],
                    dark * 1.04F, dark * 1.06F, dark * 1.08F, alpha);
        }
    }

    private static void quad(BufferBuilder builder, Matrix4f matrix,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float r, float g, float b, float a) {
        vertex(builder, matrix, x0, y0, z0, r, g, b, a);
        vertex(builder, matrix, x1, y1, z1, r, g, b, a);
        vertex(builder, matrix, x2, y2, z2, r, g, b, a);
        vertex(builder, matrix, x3, y3, z3, r, g, b, a);
    }

    private static void vertex(BufferBuilder builder, Matrix4f matrix,
                               float x, float y, float z,
                               float r, float g, float b, float a) {
        builder.vertex(matrix, x, y, z)
                .color(r, g, b, a)
                .endVertex();
    }

    private static float[] makeCrackSegments(int x, int y, int z, int faceMask, Random random) {
        FloatBuilder values = new FloatBuilder(192);

        // On horizontal top faces, every block uses deterministic shared edge anchors.
        // Matching anchors make cracks continue across same-height neighbouring blocks
        // instead of looking like separate decals or unrelated stars on every block.
        if ((faceMask & 1) != 0) {
            float centerU = 0.46F + random.nextFloat() * 0.08F;
            float centerV = 0.46F + random.nextFloat() * 0.08F;
            for (int edge = 0; edge < 4; edge++) {
                float edgeT;
                float endU;
                float endV;
                if (edge == 0) {
                    edgeT = sharedEdgeParameter(x, z, y, 0);
                    endU = 0.015F;
                    endV = edgeT;
                } else if (edge == 1) {
                    edgeT = sharedEdgeParameter(x + 1, z, y, 0);
                    endU = 0.985F;
                    endV = edgeT;
                } else if (edge == 2) {
                    edgeT = sharedEdgeParameter(x, z + 1, y, 1);
                    endU = edgeT;
                    endV = 0.015F;
                } else {
                    edgeT = sharedEdgeParameter(x, z, y, 1);
                    endU = edgeT;
                    endV = 0.985F;
                }

                float prevU = centerU;
                float prevV = centerV;
                int pieces = 3;
                for (int p = 1; p <= pieces; p++) {
                    float t = p / (float) pieces;
                    float u = centerU + (endU - centerU) * t;
                    float v = centerV + (endV - centerV) * t;
                    if (p < pieces) {
                        float jitter = (1.0F - t) * 0.12F + 0.012F;
                        u += (random.nextFloat() - 0.5F) * jitter;
                        v += (random.nextFloat() - 0.5F) * jitter;
                    } else {
                        // Keep the endpoint exact so its neighbour meets it pixel-perfectly.
                        u = endU;
                        v = endV;
                    }
                    float halfWidth = 0.061F + random.nextFloat() * 0.040F;
                    addFaceSegment(values, 0, prevU, prevV, u, v, halfWidth);
                    prevU = u;
                    prevV = v;
                }

                // A short crooked branch breaks the clean spoke appearance.
                if (random.nextFloat() < 0.72F) {
                    float branchU = centerU + (endU - centerU) * (0.35F + random.nextFloat() * 0.28F);
                    float branchV = centerV + (endV - centerV) * (0.35F + random.nextFloat() * 0.28F);
                    float branchAngle = (float) Math.atan2(endV - centerV, endU - centerU)
                            + (random.nextBoolean() ? 1.0F : -1.0F)
                            * (0.72F + random.nextFloat() * 0.52F);
                    float branchEndU = Mth.clamp(branchU + (float) Math.cos(branchAngle)
                            * (0.18F + random.nextFloat() * 0.16F), 0.015F, 0.985F);
                    float branchEndV = Mth.clamp(branchV + (float) Math.sin(branchAngle)
                            * (0.18F + random.nextFloat() * 0.16F), 0.015F, 0.985F);
                    float branchMidU = (branchU + branchEndU) * 0.5F
                            + (random.nextFloat() - 0.5F) * 0.05F;
                    float branchMidV = (branchV + branchEndV) * 0.5F
                            + (random.nextFloat() - 0.5F) * 0.05F;
                    float branchWidth = 0.040F + random.nextFloat() * 0.028F;
                    addFaceSegment(values, 0, branchU, branchV, branchMidU, branchMidV, branchWidth);
                    addFaceSegment(values, 0, branchMidU, branchMidV,
                            branchEndU, branchEndV, branchWidth * 0.84F);
                }
            }
        }

        // Exposed vertical walls receive their own downward-running fractures.
        for (int face = 2; face < 6; face++) {
            if ((faceMask & (1 << face)) == 0) continue;
            float centerU = 0.42F + random.nextFloat() * 0.16F;
            float centerV = 0.72F + random.nextFloat() * 0.12F;
            int rays = 3 + random.nextInt(2);
            float angleOffset = random.nextFloat() * 0.7F;
            for (int ray = 0; ray < rays; ray++) {
                float angle = angleOffset + ray * ((float) Math.PI * 2.0F / rays);
                float endU = Mth.clamp(centerU + (float) Math.cos(angle) * 0.42F,
                        0.02F, 0.98F);
                float endV = Mth.clamp(centerV + (float) Math.sin(angle) * 0.68F,
                        0.02F, 0.98F);
                float prevU = centerU;
                float prevV = centerV;
                for (int p = 1; p <= 3; p++) {
                    float t = p / 3.0F;
                    float jitter = (1.0F - t) * 0.12F + 0.01F;
                    float u = centerU + (endU - centerU) * t;
                    float v = centerV + (endV - centerV) * t;
                    if (p < 3) {
                        u += (random.nextFloat() - 0.5F) * jitter;
                        v += (random.nextFloat() - 0.5F) * jitter;
                    }
                    float halfWidth = 0.050F + random.nextFloat() * 0.038F;
                    addFaceSegment(values, face, prevU, prevV, u, v, halfWidth);
                    prevU = u;
                    prevV = v;
                }
                if (random.nextFloat() < 0.58F) {
                    float branchU = (centerU + endU) * 0.5F;
                    float branchV = (centerV + endV) * 0.5F;
                    float branchEndU = Mth.clamp(branchU + (random.nextFloat() - 0.5F) * 0.40F,
                            0.02F, 0.98F);
                    float branchEndV = Mth.clamp(branchV - 0.18F - random.nextFloat() * 0.22F,
                            0.02F, 0.98F);
                    addFaceSegment(values, face, branchU, branchV,
                            branchEndU, branchEndV, 0.044F + random.nextFloat() * 0.024F);
                }
            }
        }
        return values.toArray();
    }

    private static float sharedEdgeParameter(int edgeA, int edgeB, int y, int axis) {
        long hash = 0x9E3779B97F4A7C15L;
        hash ^= (long) edgeA * 0x632BE59BD9B4E019L;
        hash ^= (long) edgeB * 0x9E3779B185EBCA87L;
        hash ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        hash ^= (long) axis * 0x165667B19E3779F9L;
        hash = (hash ^ (hash >>> 30)) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ (hash >>> 27)) * 0x94D049BB133111EBL;
        hash ^= hash >>> 31;
        float unit = ((hash >>> 40) & 0xFFFFFFL) / 16777215.0F;
        return 0.22F + unit * 0.56F;
    }
    private static void addFaceSegment(FloatBuilder output, int face,
                                       float u0, float v0, float u1, float v1,
                                       float halfWidth) {
        float[] a = facePoint(face, u0, v0, 0.006F);
        float[] b = facePoint(face, u1, v1, 0.006F);
        output.add(face);
        output.add(a[0]); output.add(a[1]); output.add(a[2]);
        output.add(b[0]); output.add(b[1]); output.add(b[2]);
        output.add(halfWidth);
    }
    private static float[] facePoint(int face, float u, float v, float depth) {
        float[] o = FACE_ORIGIN[face];
        float[] n = NORMAL[face];
        float[] ux = U_AXIS[face];
        float[] vx = V_AXIS[face];
        return new float[]{
                o[0] + ux[0] * (u - 0.5F) + vx[0] * (v - 0.5F) + n[0] * depth,
                o[1] + ux[1] * (u - 0.5F) + vx[1] * (v - 0.5F) + n[1] * depth,
                o[2] + ux[2] * (u - 0.5F) + vx[2] * (v - 0.5F) + n[2] * depth
        };
    }

    private static Shard[] makeShards(int x, int y, int z, int faceMask, Random random,
                                     double centerX, double centerZ) {
        int count = random.nextBoolean() ? 2 : 4;
        Shard[] shards = new Shard[count];
        float radialX = x + 0.5F - (float) centerX;
        float radialZ = z + 0.5F - (float) centerZ;
        float radialLength = (float) Math.sqrt(radialX * radialX + radialZ * radialZ);
        if (radialLength > 0.001F) {
            radialX /= radialLength;
            radialZ /= radialLength;
        } else {
            radialX = 0.0F;
            radialZ = 0.0F;
        }

        for (int shardIndex = 0; shardIndex < count; shardIndex++) {
            int face = randomFace(faceMask, random);
            int sides = 3 + random.nextInt(3);
            float centerU = 0.30F + random.nextFloat() * 0.40F;
            float centerV = 0.30F + random.nextFloat() * 0.40F;
            float radius = (0.12F + random.nextFloat() * 0.09F) * 5.0F;
            float rotation = random.nextFloat() * (float) Math.PI * 2.0F;
            float[] vertices = new float[sides * 6];

            for (int i = 0; i < sides; i++) {
                float angle = rotation + i * ((float) Math.PI * 2.0F / sides);
                float irregular = 0.68F + random.nextFloat() * 0.55F;
                float u = Mth.clamp(centerU + (float) Math.cos(angle) * radius * irregular, 0.08F, 0.92F);
                float v = Mth.clamp(centerV + (float) Math.sin(angle) * radius * irregular, 0.08F, 0.92F);
                float backDepth = -0.10F - random.nextFloat() * 0.12F;
                float frontDepth = 0.08F + random.nextFloat() * 0.11F;
                float[] back = facePoint(face, u, v, backDepth);
                float[] front = facePoint(face, u + (random.nextFloat() - 0.5F) * 0.035F,
                        v + (random.nextFloat() - 0.5F) * 0.035F, frontDepth);
                int base = i * 6;
                vertices[base] = back[0]; vertices[base + 1] = back[1]; vertices[base + 2] = back[2];
                vertices[base + 3] = front[0]; vertices[base + 4] = front[1]; vertices[base + 5] = front[2];
            }

            float vx = radialX * (0.035F + random.nextFloat() * 0.025F)
                    + (random.nextFloat() - 0.5F) * 0.035F;
            float vz = radialZ * (0.035F + random.nextFloat() * 0.025F)
                    + (random.nextFloat() - 0.5F) * 0.035F;
            float vy = 0.025F + random.nextFloat() * 0.040F;
            float tone = 0.27F + random.nextFloat() * 0.13F;
            shards[shardIndex] = new Shard(vertices, sides, vx, vy, vz, tone,
                    NORMAL[face][0], NORMAL[face][1], NORMAL[face][2]);
        }
        return shards;
    }

    private static int randomFace(int mask, Random random) {
        int count = Integer.bitCount(mask);
        int selected = random.nextInt(Math.max(1, count));
        for (int face = 0; face < 6; face++) {
            if ((mask & (1 << face)) != 0 && selected-- == 0) return face;
        }
        return 0;
    }

    public static void applyCameraShake(PoseStack poseStack, float partialTick) {
        appliedCameraShake = cameraShake(partialTick);
        if (appliedCameraShake != 0.0F) {
            poseStack.translate(0.0D, appliedCameraShake, 0.0D);
        }
    }

    public static void removeCameraShake(PoseStack poseStack) {
        if (appliedCameraShake != 0.0F) {
            poseStack.translate(0.0D, -appliedCameraShake, 0.0D);
            appliedCameraShake = 0.0F;
        }
    }

    private static float cameraShake(float partialTick) {
        if (!effectActive) return 0.0F;
        float t = elapsedTicks + partialTick;
        if (t < SHATTER_TICK || t >= CAMERA_SHAKE_END_TICK) return 0.0F;
        float progress = (t - SHATTER_TICK)
                / (float) (CAMERA_SHAKE_END_TICK - SHATTER_TICK);
        float envelope = (float) Math.pow(1.0F - progress, 1.35D);
        return (float) Math.sin((t - SHATTER_TICK) * 2.15F) * 0.22F * envelope;
    }

    private static boolean isFaceTowardCamera(BlockRecord record, Shard shard, View view) {
        double toCameraX = view.x - (record.x + 0.5D);
        double toCameraY = view.y - (record.y + 0.5D);
        double toCameraZ = view.z - (record.z + 0.5D);
        double dot = toCameraX * shard.normalX + toCameraY * shard.normalY
                + toCameraZ * shard.normalZ;
        return dot > -0.10D;
    }

    private static boolean isInView(BlockRecord record, View view) {
        double dx = record.x + 0.5D - view.x;
        double dy = record.y + 0.5D - view.y;
        double dz = record.z + 0.5D - view.z;
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (lengthSquared < 1.0E-5D) return true;
        double inverseLength = 1.0D / Math.sqrt(lengthSquared);
        double dot = (dx * view.lookX + dy * view.lookY + dz * view.lookZ) * inverseLength;
        // A wide forward cone avoids rendering most of the hidden hemisphere while
        // leaving enough margin for peripheral cracks and third-person cameras.
        return dot > -0.05D;
    }

    private static final class View {
        final double x, y, z;
        final double lookX, lookY, lookZ;

        View(Camera camera) {
            Vec3 position = camera.getPosition();
            x = position.x;
            y = position.y;
            z = position.z;
            double yaw = Math.toRadians(camera.getYRot());
            double pitch = Math.toRadians(camera.getXRot());
            double horizontal = Math.cos(pitch);
            lookX = -Math.sin(yaw) * horizontal;
            lookY = -Math.sin(pitch);
            lookZ = Math.cos(yaw) * horizontal;
        }
    }

    private static final class FloatBuilder {
        private float[] values;
        private int size;

        FloatBuilder(int initialCapacity) {
            values = new float[initialCapacity];
        }

        void add(float value) {
            if (size == values.length) {
                float[] grown = new float[values.length * 2];
                System.arraycopy(values, 0, grown, 0, size);
                values = grown;
            }
            values[size++] = value;
        }

        float[] toArray() {
            float[] result = new float[size];
            System.arraycopy(values, 0, result, 0, size);
            return result;
        }
    }

    private static final class BlockSnapshot {
        final int x, y, z;
        final BlockState state;
        final int faceMask;

        BlockSnapshot(int x, int y, int z, BlockState state, int faceMask) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.faceMask = faceMask;
        }
    }

    private static final class BlockRecord {
        final int x, y, z;
        final BlockState state;
        final int packedLight;
        final int faceMask;
        final double distance;
        final float crackStartTick;
        final float[] crackSegments;
        final Shard[] shards;

        BlockRecord(ClientLevel level, BlockSnapshot snapshot, int centerX, int centerZ) {
            x = snapshot.x;
            y = snapshot.y;
            z = snapshot.z;
            state = snapshot.state;
            faceMask = snapshot.faceMask;
            packedLight = LevelRenderer.getLightColor(level, new BlockPos(x, y, z));
            double dx = x + 0.5 - centerX;
            double dz = z + 0.5 - centerZ;
            distance = Math.sqrt(dx * dx + dz * dz);
            crackStartTick = (float) (distance / RADIUS) * CRACKS_REACH_TICK;
            long seed = (((long) x) * 341873128712L) ^ (((long) z) * 132897987541L) ^ (((long) y) * 42317861L);
            Random random = new Random(seed);
            crackSegments = makeCrackSegments(x, y, z, faceMask, random);
            shards = makeShards(x, y, z, faceMask, random, centerX, centerZ);
        }
    }

    private static final class Shard {
        final float[] vertices;
        final int vertexCount;
        final float velocityX, velocityY, velocityZ;
        final float darkness;
        final float normalX, normalY, normalZ;

        Shard(float[] vertices, int vertexCount, float velocityX, float velocityY,
              float velocityZ, float darkness, float normalX, float normalY, float normalZ) {
            this.vertices = vertices;
            this.vertexCount = vertexCount;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.velocityZ = velocityZ;
            this.darkness = darkness;
            this.normalX = normalX;
            this.normalY = normalY;
            this.normalZ = normalZ;
        }
    }

}
