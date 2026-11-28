package net.hydra.jojomod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Client-only visual effect played when the ability-four key (V by default) is pressed.
 *
 * Timeline, in seconds of real client time from the key press:
 *   1.10 -> 1.30  a very wide bright-pink band sweeps bottom to top over the whole screen.
 *   2.00          every surface/tree block within 15 blocks is cut into real 3D chunks: each
 *                 block volume is split into irregular pieces that carry that block's texture.
 *                 The pieces pull apart slightly and start a barely visible levitation.
 *   2.60          the pieces splinter into much smaller chunks and the whole screen shakes.
 *   4.00 -> 4.90  the splinters shrink and drift away, leaving only the dark starry cosmos.
 *
 * Nothing here writes to the world: block states are only read for the snapshot, no packets are
 * sent, and collision, entities and ability logic are untouched.
 */
public final class VCosmicFragmentEffect {
    /* ---- timeline, in seconds ---- */
    private static final float STRIPE_START = 1.10F;
    private static final float STRIPE_END = 1.30F;
    private static final float SHATTER_TIME = 2.00F;
    private static final float SHAKE_TIME = 2.60F;
    private static final float SHAKE_HOLD_END = 3.55F;
    private static final float DISSOLVE_TIME = 4.00F;
    private static final float END_TIME = 4.90F;

    /* ---- snapshot bounds ---- */
    private static final int RADIUS = 15;
    private static final int VERTICAL = 15;
    /** Safety net only; a normal 15-block snapshot stays well below this. */
    private static final int HARD_LIMIT = 6000;

    private static final List<BlockRecord> BLOCKS = new ArrayList<>();

    private static boolean active;
    private static boolean keyWasDown;
    private static boolean cosmosAnchored;
    private static long startMillis;
    private static Level capturedLevel;
    private static double originX;
    private static double originY;
    private static double originZ;

    private static boolean shakeApplied;
    private static float shakeDx;
    private static float shakeDy;

    private VCosmicFragmentEffect() {
    }

    /* ------------------------------------------------------------------ */
    /* world rendering                                                     */
    /* ------------------------------------------------------------------ */

    public static void renderWorld(PoseStack poseStack, MultiBufferSource buffer,
                                  Camera camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();

        boolean down = minecraft.screen == null
                && KeyInputRegistry.abilityFourKey != null
                && KeyInputRegistry.abilityFourKey.isDown();
        if (down && !keyWasDown) {
            start(minecraft);
        }
        keyWasDown = down;

        if (!active) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null
                || capturedLevel != minecraft.level) {
            stop();
            return;
        }

        float time = elapsedSeconds();
        if (time >= END_TIME) {
            stop();
            return;
        }
        if (cosmosAnchored && time > SHATTER_TIME && !ClientUtil.isUsingTimeErase) {
            stop();
            return;
        }
        if (BLOCKS.isEmpty()) {
            return;
        }

        Vec3 cameraPos = camera.getPosition();
        Vec3 view = minecraft.player.getViewVector(partialTick);

        if (time < SHATTER_TIME) {
            // Before the fracture the snapshotted blocks stand in for the hidden chunks.
            if (worldHidden()) {
                renderIntact(poseStack, buffer, cameraPos, view);
            }
            return;
        }

        if (time < SHAKE_TIME) {
            renderSpread(poseStack, buffer, cameraPos, view, time);
        } else {
            renderShattered(poseStack, buffer, cameraPos, view, time);
        }
    }

    private static boolean worldHidden() {
        return ClientUtil.isUsingTimeErase && ClientUtil.TimeErase >= ClientUtil.fadeTime;
    }

    /** Blocks behind the camera are skipped; they are a large part of the snapshot. */
    private static boolean behindCamera(Vec3 cameraPos, Vec3 view, double x, double y, double z) {
        double dx = x - cameraPos.x;
        double dy = y - cameraPos.y;
        double dz = z - cameraPos.z;
        return dx * view.x + dy * view.y + dz * view.z < -1.5D;
    }

    private static void renderIntact(PoseStack poseStack, MultiBufferSource buffer, Vec3 cameraPos,
                                     Vec3 view) {
        Minecraft minecraft = Minecraft.getInstance();
        for (BlockRecord block : BLOCKS) {
            if (behindCamera(cameraPos, view,
                    block.pos.getX() + 0.5D, block.pos.getY() + 0.5D, block.pos.getZ() + 0.5D)) {
                continue;
            }
            poseStack.pushPose();
            poseStack.translate(
                    block.pos.getX() - cameraPos.x,
                    block.pos.getY() - cameraPos.y,
                    block.pos.getZ() - cameraPos.z
            );
            minecraft.getBlockRenderer().renderSingleBlock(
                    block.state,
                    poseStack,
                    buffer,
                    LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY
            );
            poseStack.popPose();
        }
    }

    private static void renderSpread(PoseStack poseStack, MultiBufferSource buffer, Vec3 cameraPos,
                                     Vec3 view, float time) {
        float spread = smooth((time - SHATTER_TIME) / (SHAKE_TIME - SHATTER_TIME));

        for (BlockRecord block : BLOCKS) {
            if (behindCamera(cameraPos, view,
                    block.pos.getX() + 0.5D, block.pos.getY() + 0.5D, block.pos.getZ() + 0.5D)) {
                continue;
            }
            for (Shard shard : block.large) {
                float separate = 1.0F + 1.05F * spread;
                float bob = 0.020F * Mth.sin(time * 2.4F + shard.phase);

                poseStack.pushPose();
                poseStack.translate(
                        (float) (block.pos.getX() + shard.cx * separate + shard.pushX * spread
                                - cameraPos.x),
                        (float) (block.pos.getY() + shard.cy * separate + shard.pushY * spread
                                + bob - cameraPos.y),
                        (float) (block.pos.getZ() + shard.cz * separate + shard.pushZ * spread
                                - cameraPos.z)
                );
                poseStack.mulPose(Axis.XP.rotationDegrees(shard.rx * 0.35F * spread));
                poseStack.mulPose(Axis.YP.rotationDegrees(shard.ry * 0.35F * spread));
                poseStack.mulPose(Axis.ZP.rotationDegrees(shard.rz * 0.35F * spread));
                emitShard(buffer, poseStack, shard, block.sprite, 1.0F);
                poseStack.popPose();
            }
        }
    }

    private static void renderShattered(PoseStack poseStack, MultiBufferSource buffer, Vec3 cameraPos,
                                        Vec3 view, float time) {
        float split = smooth((time - SHAKE_TIME) / 0.35F);
        float dissolve = smooth((time - DISSOLVE_TIME) / (END_TIME - DISSOLVE_TIME));
        float alpha = 1.0F - dissolve;
        float rise = dissolve * 0.55F;
        float scale = 1.0F - 0.85F * dissolve;

        if (alpha <= 0.02F) {
            return;
        }

        // The big chunks crumble away during the first moments of the split.
        float residual = 1.0F - Mth.clamp((time - SHAKE_TIME) / 0.16F, 0.0F, 1.0F);
        if (residual > 0.02F) {
            for (BlockRecord block : BLOCKS) {
                if (behindCamera(cameraPos, view, block.pos.getX() + 0.5D,
                        block.pos.getY() + 0.5D, block.pos.getZ() + 0.5D)) {
                    continue;
                }
                for (Shard shard : block.large) {
                    poseStack.pushPose();
                    poseStack.translate(
                            (float) (block.pos.getX() + shard.cx * 2.05F + shard.pushX * 2.0F
                                    - cameraPos.x),
                            (float) (block.pos.getY() + shard.cy * 2.05F + shard.pushY * 2.0F
                                    - cameraPos.y),
                            (float) (block.pos.getZ() + shard.cz * 2.05F + shard.pushZ * 2.0F
                                    - cameraPos.z)
                    );
                    poseStack.scale(residual, residual, residual);
                    emitShard(buffer, poseStack, shard, block.sprite, 1.0F);
                    poseStack.popPose();
                }
            }
        }

        for (BlockRecord block : BLOCKS) {
            if (behindCamera(cameraPos, view, block.pos.getX() + 0.5D,
                    block.pos.getY() + 0.5D, block.pos.getZ() + 0.5D)) {
                continue;
            }
            for (Shard shard : block.small) {
                poseStack.pushPose();
                poseStack.translate(
                        (float) (block.pos.getX() + shard.cx * (1.0F + 1.4F * split)
                                + shard.pushX * (0.35F + 0.65F * split) - cameraPos.x),
                        (float) (block.pos.getY() + shard.cy * (1.0F + 1.4F * split)
                                + shard.pushY * (0.35F + 0.65F * split) + rise - cameraPos.y),
                        (float) (block.pos.getZ() + shard.cz * (1.0F + 1.4F * split)
                                + shard.pushZ * (0.35F + 0.65F * split) - cameraPos.z)
                );
                poseStack.scale(scale, scale, scale);
                poseStack.mulPose(Axis.XP.rotationDegrees(shard.rx * 0.6F * split
                        + time * 26.0F * shard.spin));
                poseStack.mulPose(Axis.YP.rotationDegrees(shard.ry * 0.6F * split
                        + time * 19.0F * shard.spin));
                emitShard(buffer, poseStack, shard, block.sprite, alpha);
                poseStack.popPose();
            }
        }
    }

    /**
     * Emits one irregular 3D chunk: six textured faces of a jittered box that was cut out of the
     * block volume, with the block's own texture and vanilla-like face shading.
     */
    private static void emitShard(MultiBufferSource buffer, PoseStack poseStack, Shard shard,
                                 TextureAtlasSprite sprite, float alpha) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(sprite.atlasLocation()));
        Matrix4f matrix = poseStack.last().pose();
        Matrix3f normalMatrix = poseStack.last().normal();

        float[] p = shard.corners;
        float cx = shard.centerX;
        float cy = shard.centerY;
        float cz = shard.centerZ;

        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        int light = LightTexture.FULL_BRIGHT;

        for (int face = 0; face < 6; face++) {
            int a = FACE_A[face];
            int b = FACE_B[face];
            int c = FACE_C[face];
            int d = FACE_D[face];
            float shade = FACE_SHADE[face];
            float nx = FACE_NORMAL[face * 3];
            float ny = FACE_NORMAL[face * 3 + 1];
            float nz = FACE_NORMAL[face * 3 + 2];
            int axis = FACE_UV_AXIS[face];

            vertex(consumer, matrix, normalMatrix, p, a, cx, cy, cz, u0, u1, v0, v1, axis,
                    shade, alpha, nx, ny, nz, light);
            vertex(consumer, matrix, normalMatrix, p, b, cx, cy, cz, u0, u1, v0, v1, axis,
                    shade, alpha, nx, ny, nz, light);
            vertex(consumer, matrix, normalMatrix, p, c, cx, cy, cz, u0, u1, v0, v1, axis,
                    shade, alpha, nx, ny, nz, light);
            vertex(consumer, matrix, normalMatrix, p, d, cx, cy, cz, u0, u1, v0, v1, axis,
                    shade, alpha, nx, ny, nz, light);
        }
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix,
                               float[] corners, int index, float cx, float cy, float cz,
                               float u0, float u1, float v0, float v1, int axis,
                               float shade, float alpha, float nx, float ny, float nz, int light) {
        float x = corners[index * 3] - cx;
        float y = corners[index * 3 + 1] - cy;
        float z = corners[index * 3 + 2] - cz;

        float uvU;
        float uvV;
        if (axis == 0) {
            uvU = corners[index * 3];
            uvV = corners[index * 3 + 2];
        } else if (axis == 1) {
            uvU = corners[index * 3];
            uvV = 1.0F - corners[index * 3 + 1];
        } else {
            uvU = corners[index * 3 + 2];
            uvV = 1.0F - corners[index * 3 + 1];
        }

        consumer.vertex(matrix, x, y, z)
                .color(shade, shade, shade, alpha)
                .uv(u0 + (u1 - u0) * uvU, v0 + (v1 - v0) * uvV)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normalMatrix, nx, ny, nz)
                .endVertex();
    }

    /* ------------------------------------------------------------------ */
    /* screen shake                                                        */
    /* ------------------------------------------------------------------ */

    /** Called at the head of the level render, where the pose is still view aligned. */
    public static void applyScreenShake(PoseStack poseStack) {
        shakeApplied = false;
        shakeDx = 0.0F;
        shakeDy = 0.0F;
        if (!active) {
            return;
        }
        float time = elapsedSeconds();
        if (time < SHAKE_TIME || time > DISSOLVE_TIME) {
            return;
        }
        float amplitude;
        if (time < SHAKE_HOLD_END) {
            amplitude = 0.115F * Mth.clamp((time - SHAKE_TIME) / 0.12F, 0.0F, 1.0F);
        } else {
            amplitude = 0.115F * (1.0F - Mth.clamp(
                    (time - SHAKE_HOLD_END) / (DISSOLVE_TIME - SHAKE_HOLD_END), 0.0F, 1.0F));
        }
        if (amplitude <= 0.0005F) {
            return;
        }

        long millis = Util.getMillis();
        shakeDx = amplitude * Mth.sin(millis * 0.075F);
        shakeDy = amplitude * 0.85F * Mth.sin(millis * 0.098F + 1.9F);
        poseStack.translate(shakeDx, shakeDy, 0.0F);
        shakeApplied = true;
    }

    /** Called at the tail of the level render to undo the shake offset. */
    public static void clearScreenShake(PoseStack poseStack) {
        if (shakeApplied) {
            poseStack.translate(-shakeDx, -shakeDy, 0.0F);
            shakeApplied = false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* screen overlay: the wide pink sweep                                 */
    /* ------------------------------------------------------------------ */

    public static void renderScreenOverlay(GuiGraphics graphics) {
        if (!active) {
            return;
        }
        float time = elapsedSeconds();
        if (time < STRIPE_START || time > STRIPE_END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float progress = (time - STRIPE_START) / (STRIPE_END - STRIPE_START);
        float bandHeight = height * 0.40F;
        float top = height - progress * (height + bandHeight);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        int slices = 32;
        for (int i = 0; i < slices; i++) {
            float u = (i + 0.5F) / slices;
            float profile = (float) Math.pow(Mth.sin(u * (float) Math.PI), 0.55F);
            int alpha = (int) Mth.clamp(profile * 245.0F, 0.0F, 255.0F);
            if (alpha <= 2) {
                continue;
            }
            int red = 255;
            int green = (int) (55.0F + 95.0F * profile);
            int blue = (int) (128.0F + 78.0F * profile);
            int color = (alpha << 24) | (red << 16) | (green << 8) | blue;

            int y0 = Mth.floor(top + (i / (float) slices) * bandHeight);
            int y1 = (int) Math.ceil(top + ((i + 1) / (float) slices) * bandHeight);
            if (y1 <= 0 || y0 >= height) {
                continue;
            }
            graphics.fill(0, Math.max(0, y0), width, Math.min(height, y1), color);
        }

        RenderSystem.disableBlend();
    }

    /* ------------------------------------------------------------------ */
    /* lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    private static void start(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        capturedLevel = minecraft.level;
        originX = minecraft.player.getX();
        originY = minecraft.player.getY();
        originZ = minecraft.player.getZ();
        cosmosAnchored = ClientUtil.isUsingTimeErase;
        startMillis = Util.getMillis();
        shakeApplied = false;
        active = true;
        capture(capturedLevel);
    }

    public static void stop() {
        active = false;
        keyWasDown = false;
        cosmosAnchored = false;
        capturedLevel = null;
        shakeApplied = false;
        BLOCKS.clear();
    }

    private static float elapsedSeconds() {
        return (Util.getMillis() - startMillis) / 1000.0F;
    }

    private static float smooth(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    /* ------------------------------------------------------------------ */
    /* snapshot + fragment generation                                      */
    /* ------------------------------------------------------------------ */

    private static void capture(Level level) {
        BLOCKS.clear();

        int centerX = Mth.floor(originX);
        int centerY = Mth.floor(originY);
        int centerZ = Mth.floor(originZ);
        int minY = Math.max(level.getMinBuildHeight(), centerY - VERTICAL);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centerY + VERTICAL);

        Random random = new Random((((long) centerX) << 32) ^ (((long) centerZ) << 7)
                ^ centerY ^ 0x4B435632L);

        for (int x = centerX - RADIUS; x <= centerX + RADIUS; x++) {
            int dx = x - centerX;
            for (int z = centerZ - RADIUS; z <= centerZ + RADIUS; z++) {
                int dz = z - centerZ;
                int flat = dx * dx + dz * dz;
                if (flat > RADIUS * RADIUS) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    int dy = y - centerY;
                    if (dy * dy + flat > RADIUS * RADIUS + RADIUS) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!isCrackable(state)) {
                        continue;
                    }
                    if (!isExposed(level, pos)) {
                        continue;
                    }
                    if (BLOCKS.size() >= HARD_LIMIT) {
                        break;
                    }
                    double distance = Math.sqrt(flat + (double) dy * dy);
                    int large = distance <= 5.5D ? 3 : (distance <= 10.5D ? 2 : 1);
                    BLOCKS.add(new BlockRecord(pos.immutable(), state,
                            spriteOf(state), buildFragments(large, random)));
                }
            }
        }
    }

    private static TextureAtlasSprite spriteOf(BlockState state) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getBlockRenderer().getBlockModelShaper().getBlockModel(state).getParticleIcon();
    }

    /** Surface blocks and trees only: anything with a real model and no fluid. */
    private static boolean isCrackable(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        return state.getFluidState().isEmpty();
    }

    private static boolean isExposed(Level level, BlockPos pos) {
        for (int i = 0; i < 6; i++) {
            BlockPos neighbor = pos.offset(
                    i == 0 ? 1 : (i == 1 ? -1 : 0),
                    i == 2 ? 1 : (i == 3 ? -1 : 0),
                    i == 4 ? 1 : (i == 5 ? -1 : 0)
            );
            if (!level.getBlockState(neighbor).canOcclude()) {
                return true;
            }
        }
        return false;
    }

    private static Fragments buildFragments(int largeCount, Random random) {
        Fragments fragments = new Fragments();
        List<float[]> boxes = new ArrayList<>();
        splitVolume(boxes, new float[]{0.0F, 0.0F, 0.0F, 1.0F, 1.0F, 1.0F}, largeCount, random);

        for (float[] box : boxes) {
            Shard large = makeShard(box, random, 9.0F);
            fragments.large.add(large);

            List<float[]> smallBoxes = new ArrayList<>();
            splitVolume(smallBoxes, box, 2, random);
            for (float[] smallBox : smallBoxes) {
                Shard small = makeShard(smallBox, random, 26.0F);
                pieces(small, large);
                fragments.small.add(small);
            }
        }
        return fragments;
    }

    private static void pieces(Shard small, Shard parent) {
        small.pushX = parent.pushX * 1.8F + small.pushX * 0.6F;
        small.pushY = parent.pushY * 1.8F + small.pushY * 0.6F;
        small.pushZ = parent.pushZ * 1.8F + small.pushZ * 0.6F;
    }

    /** Recursive guillotine split so the pieces really fill the block volume. */
    private static void splitVolume(List<float[]> out, float[] box, int count, Random random) {
        if (count <= 1) {
            out.add(box);
            return;
        }
        int axis = random.nextInt(3);
        float t = 0.34F + random.nextFloat() * 0.32F;
        float[] first = box.clone();
        float[] second = box.clone();
        if (axis == 0) {
            float cut = box[0] + (box[3] - box[0]) * t;
            first[3] = cut;
            second[0] = cut;
        } else if (axis == 1) {
            float cut = box[1] + (box[4] - box[1]) * t;
            first[4] = cut;
            second[1] = cut;
        } else {
            float cut = box[2] + (box[5] - box[2]) * t;
            first[5] = cut;
            second[2] = cut;
        }
        int left = count / 2;
        splitVolume(out, first, left, random);
        splitVolume(out, second, count - left, random);
    }

    private static Shard makeShard(float[] box, Random random, float maxRotation) {
        float[] corners = new float[24];
        int index = 0;
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    corners[index * 3] = jitter(box[x == 0 ? 0 : 3], 0.035F, random);
                    corners[index * 3 + 1] = jitter(box[y == 0 ? 1 : 4], 0.035F, random);
                    corners[index * 3 + 2] = jitter(box[z == 0 ? 2 : 5], 0.035F, random);
                    index++;
                }
            }
        }

        float cx = 0.0F;
        float cy = 0.0F;
        float cz = 0.0F;
        for (int i = 0; i < 8; i++) {
            cx += corners[i * 3];
            cy += corners[i * 3 + 1];
            cz += corners[i * 3 + 2];
        }
        cx /= 8.0F;
        cy /= 8.0F;
        cz /= 8.0F;

        float pushX = randomBetween(random, -1.0F, 1.0F);
        float pushY = randomBetween(random, -0.4F, 1.0F);
        float pushZ = randomBetween(random, -1.0F, 1.0F);
        float length = Math.max(0.001F,
                (float) Math.sqrt(pushX * pushX + pushY * pushY + pushZ * pushZ));

        return new Shard(
                corners, cx, cy, cz,
                pushX / length, pushY / length, pushZ / length,
                randomBetween(random, -maxRotation, maxRotation),
                randomBetween(random, -maxRotation, maxRotation),
                randomBetween(random, -maxRotation, maxRotation),
                random.nextFloat() * 6.2832F,
                0.5F + random.nextFloat()
        );
    }

    private static float jitter(float value, float amount, Random random) {
        float jittered = value + randomBetween(random, -amount, amount);
        return Mth.clamp(jittered, 0.0F, 1.0F);
    }

    private static float randomBetween(Random random, float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    /* ------------------------------------------------------------------ */
    /* data holders                                                        */
    /* ------------------------------------------------------------------ */

    /* Faces of a box, corner order: bit0 = x, bit1 = y, bit2 = z. */
    private static final int[] FACE_A = {2, 0, 4, 0, 1, 0};
    private static final int[] FACE_B = {3, 1, 5, 1, 3, 2};
    private static final int[] FACE_C = {7, 5, 7, 3, 7, 6};
    private static final int[] FACE_D = {6, 4, 6, 2, 5, 4};
    private static final float[] FACE_SHADE = {1.0F, 0.52F, 0.80F, 0.80F, 0.63F, 0.63F};
    private static final float[] FACE_NORMAL = {
            0, 1, 0,
            0, -1, 0,
            0, 0, 1,
            0, 0, -1,
            1, 0, 0,
            -1, 0, 0
    };
    /** 0 = top/bottom mapping, 1 = side mapping using x/y, 2 = side mapping using z/y. */
    private static final int[] FACE_UV_AXIS = {0, 0, 2, 2, 1, 1};

    private static final class Fragments {
        final List<Shard> large = new ArrayList<>();
        final List<Shard> small = new ArrayList<>();
    }

    private static final class BlockRecord {
        final BlockPos pos;
        final BlockState state;
        final TextureAtlasSprite sprite;
        final List<Shard> large;
        final List<Shard> small;

        BlockRecord(BlockPos pos, BlockState state, TextureAtlasSprite sprite, Fragments fragments) {
            this.pos = pos;
            this.state = state;
            this.sprite = sprite;
            this.large = fragments.large;
            this.small = fragments.small;
        }
    }

    private static final class Shard {
        final float[] corners;
        final float centerX;
        final float centerY;
        final float centerZ;
        float pushX;
        float pushY;
        float pushZ;
        final float rx;
        final float ry;
        final float rz;
        final float phase;
        final float spin;

        Shard(float[] corners, float centerX, float centerY, float centerZ,
              float pushX, float pushY, float pushZ,
              float rx, float ry, float rz, float phase, float spin) {
            this.corners = corners;
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.pushX = pushX;
            this.pushY = pushY;
            this.pushZ = pushZ;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
            this.phase = phase;
            this.spin = spin;
        }
    }
}
