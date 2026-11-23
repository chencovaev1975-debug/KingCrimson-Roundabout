package net.hydra.jojomod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/** Fast additive neon slash drawn in screen-space over the HUD. */
public final class TimeEraseSlashRenderer {
    private TimeEraseSlashRenderer() {}

    public static void render(GuiGraphics graphics, float partialTick, int screenWidth, int screenHeight) {
        float reveal = TimeEraseFractureEffect.getSlashRevealProgress(partialTick);
        if (reveal < 0.0F || screenWidth <= 0 || screenHeight <= 0) return;

        float fade = TimeEraseFractureEffect.getSlashFade(partialTick);
        if (fade <= 0.0F) return;

        Matrix4f matrix = graphics.pose().last().pose();
        RenderSystem.enableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        // Additive blending: the pink halo builds light without a flat opaque ribbon.
        RenderSystem.blendFunc(770, 1);
        try {
            drawRibbon(matrix, screenWidth, screenHeight, reveal, fade,
                    62.0F, 1.0F, 0.0F, 0.34F, 0.13F);
            drawRibbon(matrix, screenWidth, screenHeight, reveal, fade,
                    38.0F, 1.0F, 0.015F, 0.48F, 0.46F);
            drawRibbon(matrix, screenWidth, screenHeight, reveal, fade,
                    22.0F, 1.0F, 0.68F, 0.78F, 0.82F);
            drawRibbon(matrix, screenWidth, screenHeight, reveal, fade,
                    8.0F, 1.0F, 1.0F, 1.0F, 0.90F);
        } finally {
            // Leave the HUD's expected state active for the remaining GUI passes.
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableBlend();
            RenderSystem.disableDepthTest();
        }
    }

    private static void drawRibbon(Matrix4f matrix, int width, int height,
                                   float reveal, float fade, float fullWidth,
                                   float red, float green, float blue, float alpha) {
        int segments = Math.max(1, (int) Math.ceil(reveal * 72.0F));
        float end = Math.max(0.001F, Math.min(1.0F, reveal));

        // A curved slash begins below the lower edge, bends left, then sweeps
        // back toward the upper edge. Both ends taper to a sharp point.
        float p0x = width * 0.73F;
        float p0y = height * 1.10F;
        float p1x = width * 0.25F;
        float p1y = height * 0.52F;
        float p2x = width * 0.58F;
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
            float taper0 = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * t0)), 0.58D);
            float taper1 = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * t1)), 0.58D);
            float half0 = fullWidth * 0.5F * taper0 * fade;
            float half1 = fullWidth * 0.5F * taper1 * fade;
            float vertexAlpha0 = alpha * fade;
            float vertexAlpha1 = alpha * fade;

            vertex(builder, matrix, a[0] + normalX * half0, a[1] + normalY * half0,
                    red, green, blue, vertexAlpha0);
            vertex(builder, matrix, a[0] - normalX * half0, a[1] - normalY * half0,
                    red, green, blue, vertexAlpha0);
            vertex(builder, matrix, b[0] - normalX * half1, b[1] - normalY * half1,
                    red, green, blue, vertexAlpha1);
            vertex(builder, matrix, b[0] + normalX * half1, b[1] + normalY * half1,
                    red, green, blue, vertexAlpha1);
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
