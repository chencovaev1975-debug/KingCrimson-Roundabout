package net.hydra.jojomod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * The old King Crimson screen transition: broken magenta/violet pixel tears.
 * The transition is assembled from stepped horizontal fragments and curved
 * side ribbons. It deliberately does not draw one clean, solid pink stripe.
 *
 * The same renderer is shared by the original Time Erase and the B-triggered
 * second sequence, so their opening/closing transition remains consistent.
 */
public final class TimeEraseSlashRenderer {
    private static final int SIDE_STEPS = 22;
    private static final int HORIZONTAL_TEAR_COUNT = 13;

    private TimeEraseSlashRenderer() {}

    public static void render(GuiGraphics graphics, float partialTick,
                              int screenWidth, int screenHeight) {
        boolean secondScene = TimeEraseSecondEffect.isActive();
        float progress = secondScene
                ? TimeEraseSecondEffect.getTransitionProgress()
                : TimeEraseFractureEffect.getSlashRevealProgress(partialTick);
        if (progress < 0.0F || screenWidth <= 0 || screenHeight <= 0) return;

        float fade = secondScene
                ? TimeEraseSecondEffect.getTransitionFade()
                : TimeEraseFractureEffect.getSlashFade(partialTick);
        if (fade <= 0.0F) return;

        // A reversed copy of the same glitch is used for the closing transition.
        float pulse = (float) Math.sin(Math.PI * clamp01(progress));
        float intensity = Math.min(1.0F, (0.34F + pulse * 0.66F) * fade);
        float travel = (progress - 0.5F) * 2.0F;

        graphics.flush();
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(0.0D, 0.0D, 500.0D);
        try {
            Matrix4f matrix = pose.last().pose();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

            // Irregular curved bands on the side edges. Each band is built from
            // short stepped rectangles, like the jagged purple arcs in the reference.
            drawSideRibbon(builder, matrix, screenWidth, screenHeight,
                    intensity, travel, false, 0.0F);
            drawSideRibbon(builder, matrix, screenWidth, screenHeight,
                    intensity * 0.78F, -travel * 0.84F, true, 1.0F);

            // Several independent horizontal tears cross different parts of the
            // screen. They vary in length, offset, thickness and pixel-step profile.
            for (int i = 0; i < HORIZONTAL_TEAR_COUNT; i++) {
                float seed = noise(i * 31 + 7);
                float seed2 = noise(i * 47 + 19);
                float y = screenHeight * (0.07F + (i + seed * 0.7F)
                        / (float) HORIZONTAL_TEAR_COUNT * 0.88F);
                float height = 7.0F + seed2 * (screenHeight * 0.055F);
                float width = screenWidth * (0.19F + noise(i * 59 + 5) * 0.88F);
                float x = screenWidth * (-0.12F + noise(i * 23 + 3) * 0.62F);
                float drift = travel * (i % 2 == 0 ? 1.0F : -1.0F)
                        * screenWidth * (0.12F + seed * 0.12F);
                x += drift;
                drawSteppedStrip(builder, matrix, x, y, width, height,
                        intensity * (0.46F + seed2 * 0.48F),
                        i * 17 + 29, i % 4);
            }

            // A few short detached fragments make the distortion look torn rather
            // than like continuous bands. These are deliberately sparse.
            for (int i = 0; i < 18; i++) {
                float seed = noise(i * 71 + 13);
                float seed2 = noise(i * 37 + 101);
                float width = screenWidth * (0.035F + seed * 0.20F);
                float height = 3.0F + seed2 * 12.0F;
                float x = screenWidth * seed + travel * screenWidth
                        * (i % 2 == 0 ? 0.07F : -0.07F);
                float y = screenHeight * (0.04F + seed2 * 0.92F);
                drawSteppedStrip(builder, matrix, x, y, width, height,
                        intensity * (0.28F + seed * 0.42F),
                        i * 97 + 41, i % 3);
            }

            BufferUploader.drawWithShader(builder.end());
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableBlend();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            pose.popPose();
        }
    }

    private static void drawSideRibbon(BufferBuilder builder, Matrix4f matrix,
                                       int screenWidth, int screenHeight,
                                       float alpha, float travel,
                                       boolean rightSide, float phase) {
        for (int i = 0; i < SIDE_STEPS; i++) {
            float t = i / (float) (SIDE_STEPS - 1);
            float bulge = (float) Math.sin(Math.PI * t);
            float xCurve = 0.025F + bulge * (0.16F + 0.08F * noise(i * 19 + 11));
            float y = screenHeight * (0.025F + t * 0.95F);
            float width = screenWidth * (0.09F + bulge * 0.23F);
            float height = 9.0F + noise(i * 29 + 17) * 24.0F;
            float stepOffset = (noise(i * 43 + 23) - 0.5F) * 16.0F;

            float x = rightSide
                    ? screenWidth * (1.0F - xCurve) - width + stepOffset
                    : screenWidth * xCurve + stepOffset;
            x += travel * screenWidth * (rightSide ? -0.10F : 0.10F);

            // Break some segments into smaller detached pieces; the empty spaces
            // between them are essential to the fragmented ring-like silhouette.
            if ((i + (int) (phase * 3.0F)) % 5 == 2) {
                width *= 0.48F;
                x += rightSide ? width * 0.42F : width * 0.22F;
            }
            drawSteppedStrip(builder, matrix, x, y, width, height,
                    alpha * (0.42F + bulge * 0.50F),
                    i * 53 + (rightSide ? 71 : 5), i % 4);
        }
    }

    /** Draws one band as 2-5 horizontally offset rows, producing chunky pixel steps. */
    private static void drawSteppedStrip(BufferBuilder builder, Matrix4f matrix,
                                         float x, float y, float width, float height,
                                         float alpha, int seed, int profile) {
        if (width <= 1.0F || height <= 1.0F || alpha <= 0.005F) return;
        int rows = 2 + Math.floorMod(seed, 4);
        float rowHeight = height / rows;
        float baseJitter = Math.min(14.0F, Math.max(3.0F, height * 0.45F));

        for (int row = 0; row < rows; row++) {
            float rowNoise = noise(seed * 7 + row * 13 + 3);
            float xJitter = (rowNoise - 0.5F) * baseJitter;
            float left = x + xJitter;
            float right = x + width + (noise(seed * 11 + row * 17 + 9) - 0.5F)
                    * baseJitter * 1.8F;
            if (profile == 1 && row == rows - 1) right -= width * 0.18F;
            if (profile == 2 && row == 0) left += width * 0.14F;
            if (profile == 3 && row == rows / 2) {
                left -= width * 0.08F;
                right += width * 0.10F;
            }
            float top = y + row * rowHeight;
            float bottom = top + rowHeight + 1.0F;

            float mix = row / (float) Math.max(1, rows - 1);
            float red = 0.54F + mix * 0.30F;
            float green = 0.055F + mix * 0.13F;
            float blue = 0.62F + (1.0F - mix) * 0.24F;
            float rowAlpha = Math.min(0.76F, alpha * (0.76F + rowNoise * 0.24F));

            // A thin pale-magenta highlight lies on one edge of selected strips.
            if ((seed + row) % 3 == 0) {
                quad(builder, matrix, left, top, right, top + Math.max(2.0F, rowHeight * 0.22F),
                        0.84F, 0.35F, 0.88F, rowAlpha * 0.58F);
            }
            quad(builder, matrix, left, top, right, bottom, red, green, blue, rowAlpha);
            if (row == rows - 1 && (seed & 1) == 0) {
                float shardWidth = Math.min(width * 0.22F, 34.0F + rowNoise * 42.0F);
                quad(builder, matrix, right - shardWidth, top,
                        right + shardWidth * 0.24F, bottom + 3.0F,
                        0.68F, 0.10F, 0.74F, rowAlpha * 0.72F);
            }
        }
    }

    private static void quad(BufferBuilder builder, Matrix4f matrix,
                             float left, float top, float right, float bottom,
                             float red, float green, float blue, float alpha) {
        float a = Math.max(0.0F, Math.min(0.82F, alpha));
        builder.vertex(matrix, left, top, 0.0F).color(red, green, blue, a).endVertex();
        builder.vertex(matrix, right, top, 0.0F).color(red, green, blue, a).endVertex();
        builder.vertex(matrix, right, bottom, 0.0F).color(red, green, blue, a).endVertex();
        builder.vertex(matrix, left, bottom, 0.0F).color(red, green, blue, a).endVertex();
    }

    private static float noise(int seed) {
        float value = (float) Math.sin(seed * 78.233F + 17.17F) * 43758.5453F;
        return value - (float) Math.floor(value);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
