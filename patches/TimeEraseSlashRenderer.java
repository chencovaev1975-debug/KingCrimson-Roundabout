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

/** A broad, soft pink screen cut that travels vertically from bottom to top. */
public final class TimeEraseSlashRenderer {
    private static final float BAND_HEIGHT = 300.0F;
    private static final float ARCH_HEIGHT = 22.0F;
    private static final int HORIZONTAL_SEGMENTS = 72;
    private static final int VERTICAL_SEGMENTS = 12;

    private TimeEraseSlashRenderer() {}

    public static void render(GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        float progress = TimeEraseFractureEffect.getSlashRevealProgress(partialTick);
        if (progress < 0.0F || screenWidth <= 0 || screenHeight <= 0) return;

        float fade = TimeEraseFractureEffect.getSlashFade(partialTick);
        if (fade <= 0.0F) return;

        // Flush queued vanilla/mod GUI quads before drawing the cut directly.
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

            // Extra-wide soft edge halo, mid feather and a pink centre. There is no white core.
            drawBandLayer(matrix, screenWidth, screenHeight, progress, fade,
                    360.0F, 0.04F, 1.0F, 0.078F, 0.576F);
            drawBandLayer(matrix, screenWidth, screenHeight, progress, fade,
                    330.0F, 0.08F, 1.0F, 0.20F, 0.60F);
            drawBandLayer(matrix, screenWidth, screenHeight, progress, fade,
                    BAND_HEIGHT, 0.30F, 1.0F, 0.078F, 0.576F);
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

    private static void drawBandLayer(Matrix4f matrix, int width, int height,
                                      float progress, float fade, float bandHeight,
                                      float maximumAlpha, float red, float green, float blue) {
        float halfHeight = bandHeight * 0.5F;
        // At progress 0 the entire band is below the screen; at 1 it has left
        // above the screen. X is only the span of the band, never its direction.
        float centerY = height + halfHeight - progress * (height + bandHeight);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (int xSegment = 0; xSegment < HORIZONTAL_SEGMENTS; xSegment++) {
            float u0 = xSegment / (float) HORIZONTAL_SEGMENTS;
            float u1 = (xSegment + 1) / (float) HORIZONTAL_SEGMENTS;
            float x0 = u0 * width;
            float x1 = u1 * width;
            float curve0 = centerY - (float) Math.sin(Math.PI * u0) * ARCH_HEIGHT;
            float curve1 = centerY - (float) Math.sin(Math.PI * u1) * ARCH_HEIGHT;

            for (int ySegment = 0; ySegment < VERTICAL_SEGMENTS; ySegment++) {
                float v0 = ySegment / (float) VERTICAL_SEGMENTS;
                float v1 = (ySegment + 1) / (float) VERTICAL_SEGMENTS;
                float middle = (v0 + v1) * 0.5F;
                float edgeFade = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * middle)), 1.35D);
                float alpha = maximumAlpha * edgeFade * fade;

                float topOffset0 = -halfHeight + v0 * bandHeight;
                float topOffset1 = -halfHeight + v1 * bandHeight;
                vertex(builder, matrix, x0, curve0 + topOffset0, red, green, blue, alpha);
                vertex(builder, matrix, x1, curve1 + topOffset0, red, green, blue, alpha);
                vertex(builder, matrix, x1, curve1 + topOffset1, red, green, blue, alpha);
                vertex(builder, matrix, x0, curve0 + topOffset1, red, green, blue, alpha);
            }
        }

        BufferUploader.drawWithShader(builder.end());
    }

    private static void vertex(BufferBuilder builder, Matrix4f matrix,
                               float x, float y, float red, float green,
                               float blue, float alpha) {
        builder.vertex(matrix, x, y, 0.0F)
                .color(red, green, blue, alpha)
                .endVertex();
    }
}
