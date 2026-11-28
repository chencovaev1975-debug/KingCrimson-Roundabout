package net.hydra.jojomod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Client-only visual effect played when the ability-four key (V by default) is pressed.
 *
 * Timeline (seconds after the key press, real client time, not ticks):
 *   1.10 - 1.45  a very wide bright-pink band sweeps bottom to top over the whole screen.
 *   2.00         the nearby surface/tree blocks break into large and medium irregular
 *                fragments that keep their own block textures; they pull apart slightly
 *                and start a barely visible up/down levitation.
 *   2.60         the fragments start shaking hard and split into much smaller pieces.
 *   4.00 - 4.90  every small piece shrinks and drifts away, so only the dark starry
 *                Time Erase sky is left.
 *
 * Nothing here writes to the world: block states are only read for the snapshot, no
 * packets are sent, collision/entities/ability logic are untouched.
 */
public final class VCosmicFragmentEffect {
    /* ---- timeline, in seconds ---- */
    private static final float STRIPE_START = 1.10F;
    private static final float STRIPE_END = 1.45F;
    private static final float SHATTER_TIME = 2.00F;
    private static final float SHAKE_TIME = 2.60F;
    private static final float DISSOLVE_TIME = 4.00F;
    private static final float END_TIME = 4.90F;

    /* ---- snapshot bounds ---- */
    private static final int RADIUS = 15;
    private static final int VERTICAL_DOWN = 10;
    private static final int VERTICAL_UP = 10;
    private static final int MAX_BLOCKS = 520;

    private static final List<BlockRecord> BLOCKS = new ArrayList<>();

    private static boolean active;
    private static boolean keyWasDown;
    private static boolean cosmosAnchored;
    private static long startMillis;
    private static Level capturedLevel;
    private static double originX;
    private static double originY;
    private static double originZ;

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
        // If the effect was started during Time Erase, drop it when the ability ends so
        // fragments never float over a normal, fully rendered world.
        if (cosmosAnchored && time > SHATTER_TIME && !ClientUtil.isUsingTimeErase) {
            stop();
            return;
        }
        if (BLOCKS.isEmpty()) {
            return;
        }

        Vec3 cameraPos = camera.getPosition();

        if (time < SHATTER_TIME) {
            // Before the fracture the snapshotted blocks simply stand in for the hidden
            // chunks. While the native chunks are still drawn they are left alone.
            if (worldHidden()) {
                renderIntact(poseStack, buffer, cameraPos);
            }
            return;
        }

        if (time < SHAKE_TIME) {
            renderSpread(poseStack, buffer, cameraPos, time);
        } else {
            renderShattered(poseStack, buffer, cameraPos, time);
        }
    }

    private static boolean worldHidden() {
        return ClientUtil.isUsingTimeErase && ClientUtil.TimeErase >= ClientUtil.fadeTime;
    }

    private static void renderIntact(PoseStack poseStack, MultiBufferSource buffer, Vec3 cameraPos) {
        for (BlockRecord block : BLOCKS) {
            poseStack.pushPose();
            poseStack.translate(
                    block.pos.getX() - cameraPos.x,
                    block.pos.getY() - cameraPos.y,
                    block.pos.getZ() - cameraPos.z
            );
            drawPiece(poseStack, buffer, block.state, 1.0F, 1.0F, 1.0F);
            poseStack.popPose();
        }
    }

    private static void renderSpread(PoseStack poseStack, MultiBufferSource buffer,
                                     Vec3 cameraPos, float time) {
        float spread = smooth((time - SHATTER_TIME) / (SHAKE_TIME - SHATTER_TIME));

        for (BlockRecord block : BLOCKS) {
            for (Shard shard : block.large) {
                // Fragments only drift apart a little and keep hovering in place.
                float separate = 1.0F + 0.85F * spread;
                float bob = 0.022F * Mth.sin(time * 2.4F + shard.phase);
                float px = block.pos.getX() + 0.5F + shard.ox * separate - cameraPos.x;
                float py = block.pos.getY() + 0.5F + shard.oy * separate + bob - cameraPos.y;
                float pz = block.pos.getZ() + 0.5F + shard.oz * separate - cameraPos.z;

                poseStack.pushPose();
                poseStack.translate(px, py, pz);
                poseStack.mulPose(Axis.XP.rotationDegrees(shard.rx * 0.55F * spread));
                poseStack.mulPose(Axis.YP.rotationDegrees(shard.ry * 0.55F * spread));
                poseStack.mulPose(Axis.ZP.rotationDegrees(shard.rz * 0.55F * spread));
                drawPiece(poseStack, buffer, block.state, shard.sx, shard.sy, shard.sz);
                poseStack.popPose();
            }
        }
    }

    private static void renderShattered(PoseStack poseStack, MultiBufferSource buffer,
                                        Vec3 cameraPos, float time) {
        float split = smooth((time - SHAKE_TIME) / (DISSOLVE_TIME - SHAKE_TIME));
        float dissolve = smooth((time - DISSOLVE_TIME) / (END_TIME - DISSOLVE_TIME));
        float shakeAmp = 0.020F + 0.070F * split;
        float fadeScale = 1.0F - dissolve;
        float rise = dissolve * 0.60F;

        // Short cross-fade: the large fragments visibly crumble away as the small ones
        // appear, so the split does not pop.
        float residual = 1.0F - Mth.clamp((time - SHAKE_TIME) / 0.16F, 0.0F, 1.0F);
        if (residual > 0.02F) {
            for (BlockRecord block : BLOCKS) {
                for (Shard shard : block.large) {
                    float px = block.pos.getX() + 0.5F + shard.ox * 1.85F - cameraPos.x;
                    float py = block.pos.getY() + 0.5F + shard.oy * 1.85F - cameraPos.y;
                    float pz = block.pos.getZ() + 0.5F + shard.oz * 1.85F - cameraPos.z;
                    poseStack.pushPose();
                    poseStack.translate(px, py, pz);
                    poseStack.mulPose(Axis.XP.rotationDegrees(shard.rx * 2.2F));
                    poseStack.mulPose(Axis.YP.rotationDegrees(shard.ry * 2.2F));
                    poseStack.mulPose(Axis.ZP.rotationDegrees(shard.rz * 2.2F));
                    drawPiece(poseStack, buffer, block.state,
                            shard.sx * residual, shard.sy * residual, shard.sz * residual);
                    poseStack.popPose();
                }
            }
        }

        if (fadeScale <= 0.02F) {
            return;
        }

        for (BlockRecord block : BLOCKS) {
            for (Shard shard : block.small) {
                // Hard shake plus a slow outward scatter of every splinter.
                float jitterX = shakeAmp * Mth.sin(time * (74.0F + shard.spinRate * 31.0F) + shard.phase);
                float jitterY = shakeAmp * Mth.sin(time * (89.0F + shard.spinRate * 27.0F) + shard.phase * 1.7F);
                float jitterZ = shakeAmp * Mth.sin(time * (63.0F + shard.spinRate * 37.0F) + shard.phase * 2.3F);

                float px = block.pos.getX() + 0.5F + shard.ox + shard.dx * split * 0.34F
                        + jitterX - cameraPos.x;
                float py = block.pos.getY() + 0.5F + shard.oy + shard.dy * split * 0.34F
                        + jitterY + rise - cameraPos.y;
                float pz = block.pos.getZ() + 0.5F + shard.oz + shard.dz * split * 0.34F
                        + jitterZ - cameraPos.z;

                poseStack.pushPose();
                poseStack.translate(px, py, pz);
                poseStack.mulPose(Axis.XP.rotationDegrees(shard.rx + time * 120.0F * shard.spinRate));
                poseStack.mulPose(Axis.YP.rotationDegrees(shard.ry + time * 95.0F * shard.spinRate));
                poseStack.mulPose(Axis.ZP.rotationDegrees(shard.rz + time * 140.0F * shard.spinRate));
                drawPiece(poseStack, buffer, block.state,
                        shard.sx * fadeScale, shard.sy * fadeScale, shard.sz * fadeScale);
                poseStack.popPose();
            }
        }
    }

    /** Draws one fragment as a scaled, textured copy of the original block model. */
    private static void drawPiece(PoseStack poseStack, MultiBufferSource buffer, BlockState state,
                                  float scaleX, float scaleY, float scaleZ) {
        if (scaleX <= 0.002F || scaleY <= 0.002F || scaleZ <= 0.002F) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        poseStack.scale(scaleX, scaleY, scaleZ);
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        minecraft.getBlockRenderer().renderSingleBlock(
                state,
                poseStack,
                buffer,
                LightTexture.FULL_BRIGHT,
                OverlayTexture.NO_OVERLAY
        );
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
        active = true;
        capture(capturedLevel);
    }

    public static void stop() {
        active = false;
        keyWasDown = false;
        cosmosAnchored = false;
        capturedLevel = null;
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
        int minY = Math.max(level.getMinBuildHeight(), centerY - VERTICAL_DOWN);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centerY + VERTICAL_UP);
        int radiusSquared = RADIUS * RADIUS;

        Random random = new Random((((long) centerX) << 32) ^ (((long) centerZ) << 7)
                ^ centerY ^ 0x4B435631L);
        int seen = 0;

        for (int x = centerX - RADIUS; x <= centerX + RADIUS; x++) {
            int dx = x - centerX;
            for (int z = centerZ - RADIUS; z <= centerZ + RADIUS; z++) {
                int dz = z - centerZ;
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!isCrackable(state)) {
                        continue;
                    }
                    if (!isExposed(level, pos)) {
                        continue;
                    }
                    seen++;
                    BlockRecord candidate = new BlockRecord(pos.immutable(), state);
                    if (BLOCKS.size() < MAX_BLOCKS) {
                        BLOCKS.add(candidate);
                    } else {
                        int replace = random.nextInt(seen);
                        if (replace < MAX_BLOCKS) {
                            BLOCKS.set(replace, candidate);
                        }
                    }
                }
            }
        }

        for (BlockRecord block : BLOCKS) {
            buildFragments(block, random);
        }
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

    private static void buildFragments(BlockRecord block, Random random) {
        int largeCount = BLOCKS.size() > 260 ? 2 : 3;
        int smallPerLarge = BLOCKS.size() > 340 ? 1 : 2;

        for (int i = 0; i < largeCount; i++) {
            float ox = randomBetween(random, -0.19F, 0.19F);
            float oy = randomBetween(random, -0.19F, 0.19F);
            float oz = randomBetween(random, -0.19F, 0.19F);
            float base = 0.38F + random.nextFloat() * 0.30F;

            block.large.add(new Shard(
                    ox, oy, oz,
                    base * (0.78F + random.nextFloat() * 0.44F),
                    base * (0.78F + random.nextFloat() * 0.44F),
                    base * (0.78F + random.nextFloat() * 0.44F),
                    randomBetween(random, -26.0F, 26.0F),
                    randomBetween(random, -30.0F, 30.0F),
                    randomBetween(random, -26.0F, 26.0F),
                    0.0F, 0.0F, 0.0F,
                    random.nextFloat() * 6.2832F,
                    0.6F + random.nextFloat() * 0.8F
            ));

            for (int c = 0; c < smallPerLarge; c++) {
                float size = 0.10F + random.nextFloat() * 0.11F;
                float dirX = randomBetween(random, -1.0F, 1.0F);
                float dirY = randomBetween(random, -0.7F, 0.9F);
                float dirZ = randomBetween(random, -1.0F, 1.0F);
                float length = Math.max(0.001F, (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ));
                block.small.add(new Shard(
                        ox + randomBetween(random, -0.09F, 0.09F),
                        oy + randomBetween(random, -0.09F, 0.09F),
                        oz + randomBetween(random, -0.09F, 0.09F),
                        size * (0.75F + random.nextFloat() * 0.55F),
                        size * (0.75F + random.nextFloat() * 0.55F),
                        size * (0.75F + random.nextFloat() * 0.55F),
                        randomBetween(random, -180.0F, 180.0F),
                        randomBetween(random, -180.0F, 180.0F),
                        randomBetween(random, -180.0F, 180.0F),
                        dirX / length, dirY / length, dirZ / length,
                        random.nextFloat() * 6.2832F,
                        0.7F + random.nextFloat() * 1.1F
                ));
            }
        }
    }

    private static float randomBetween(Random random, float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    /* ------------------------------------------------------------------ */
    /* data holders                                                        */
    /* ------------------------------------------------------------------ */

    private static final class BlockRecord {
        final BlockPos pos;
        final BlockState state;
        final List<Shard> large = new ArrayList<>();
        final List<Shard> small = new ArrayList<>();

        BlockRecord(BlockPos pos, BlockState state) {
            this.pos = pos;
            this.state = state;
        }
    }

    private static final class Shard {
        final float ox;
        final float oy;
        final float oz;
        final float sx;
        final float sy;
        final float sz;
        final float rx;
        final float ry;
        final float rz;
        final float dx;
        final float dy;
        final float dz;
        final float phase;
        final float spinRate;

        Shard(float ox, float oy, float oz, float sx, float sy, float sz,
              float rx, float ry, float rz, float dx, float dy, float dz,
              float phase, float spinRate) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.rx = rx;
            this.ry = ry;
            this.rz = rz;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.phase = phase;
            this.spinRate = spinRate;
        }
    }
}
