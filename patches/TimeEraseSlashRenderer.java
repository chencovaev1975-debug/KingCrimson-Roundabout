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

/** Bright screen-space cut that is flushed above every normal HUD element. */
public final class TimeEraseSlashRenderer {
    private TimeEraseSlashRenderer() {}

    public static void render(GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        float reveal = TimeEraseFractureEffect.getSlashRevealProgress(partialTick);
        if (reveal < 0.0F || screenWidth <= 0 || screenHeight <= 0) return;

        float fade = TimeEraseFractureEffect.getSlashFade(partialTick);
        if (fade <= 0.0F) return;

        // Flush GuiGraphics first: otherwise buffered GUI quads can be uploaded
        // after this immediate draw and cover the slash again.
        graphics.flush();

        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        poseStack.translate(0.0D, 0.0D, 500.0D);
        try {
            Matrix4f matrix = poseStack.last().pose();
            RenderSystem.enableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(GameRenderer::getPositionColorShader);

            // Soft halo uses additive light; the central stroke uses normal alpha
            // blending so it stays clearly visible against bright and dark scenes.
            RenderSystem.blendFunc(770, 1);
            drawRibbon(matrix, screenWidth, screenHeight, reveal, fade,
                    70.0F, 1.0F, 0.0F, 0.20F, 0.42F);
            drawRibbon(matrix, screenWidth, screenHeight, reveal, fade,
                    46.0F, 1.0F, 0.005F, 0.32F, 0.56F);

            RenderSystem.defaultBlendFunc();
            drawRibbon(matrix, screenWidth, screenHeight, reveal, fade,
                    23.0F, 1.0F, 0.025F, 0.52F, 0.92F);
            drawRibbon(matrix, screenWidth, screenHeight, reveal, fade,
                    8.0F, 1.0F, 0.82F, 0.88F, 1.0F);
        } finally {
            RenderSystem.depthMask(true);
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.enableBlend();
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            poseStack.popPose();
        }
    }

    private static void drawRibbon(Matrix4f matrix, int width, int height,
                                   float reveal, float fade, float fullWidth,
                                   float red, float green, float blue, float alpha) {
        int segments = Math.max(1, (int) Math.ceil(reveal * 96.0F));
        float end = Math.max(0.001F, Math.min(1.0F, reveal));

        // One continuous curved cut: it enters below the frame, bends left
        // through the center and exits above the frame.
        float p0x = width * 0.74F;
        float p0y = height * 1.10F;
        float p1x = width * 0.18F;
        float p1y = height * 0.48F;
        float p2x = width * 0.55F;
        float p2y = -height * 0.12F;

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        for (int i = 0; i < segments; i++) {
            float t0 = end * i / segments;
            float t1 = end * (i + 1) / segments;
            float[] a = pointOnCurve(t0, p0x, p0y, p1x, p1y, p2x, p2y);
            float[] b = pointOnCurve(t1, p0x, p0y, p1x, p1y, p2x, p2y);

            float tangentX = b[0] - a[0];
            float tangentY = b[1] - a[1];
            float length = (float) Math.sqrt(tangentX * tangentX + tangentY * tangentY);
            if (length < 0.001F) continue;

            float normalX = -tangentY / length;
            float normalY = tangentX / length;
            float taper0 = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * t0)), 0.52D);
            float taper1 = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * t1)), 0.52D);
            float half0 = fullWidth * 0.5F * taper0 * fade;
            float half1 = fullWidth * 0.5F * taper1 * fade;
            float alpha0 = alpha * fade;
            float alpha1 = alpha * fade;

            vertex(builder, matrix, a[0] + normalX * half0, a[1] + normalY * half0,
                    red, green, blue, alpha0);
            vertex(builder, matrix, a[0] - normalX * half0, a[1] - normalY * half0,
                    red, green, blue, alpha0);
            vertex(builder, matrix, b[0] - normalX * half1, b[1] - normalY * half1,
                    red, green, blue, alpha1);
            vertex(builder, matrix, b[0] + normalX * half1, b[1] + normalY * half1,
                    red, green, blue, alpha1);
        }

        BufferUploader.drawWithShader(builder.end());
    }

    private static float[] pointOnCurve(float t, float x0, float y0,
                                        float x1, float y1, float x2, float y2) {
        float inverse = 1.0F - t;
        float x = inverse * inverse * x0 + 2.0F * inverse * t * x1 + t * t * x2;
        float y = inverse * inverse * y0 + 2.0F * inverse * t * y1 + t * t * y2;
        return new float[]{x, y};
    }

    private static void vertex(BufferBuilder builder, Matrix4f matrix,
                               float x, float y, float red, float green,
                               float blue, float alpha) {
        builder.vertex(matrix, x, y, 0.0F)
                .color(red, green, blue, alpha)
                .endVertex();
    }
}
