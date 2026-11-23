package net.hydra.jojomod.client.models.layers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.hydra.jojomod.Roundabout;
import net.hydra.jojomod.client.ClientUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;

/**
 * A contour-only Diavolo aura. The player's model is never tinted or filled:
 * narrow red flame ribbons follow the body's outer perimeter instead.
 */
public final class TimeEraseAuraLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private static final ResourceLocation WHITE_TEXTURE =
            new ResourceLocation(Roundabout.MOD_ID, "textures/misc/flat_white.png");
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public TimeEraseAuraLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount, float partialTicks,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (!(entity instanceof Player) || entity != localPlayer
                || !ClientUtil.isUsingTimeErase || entity.isInvisible()) {
            return;
        }

        float age = entity.tickCount + partialTicks;
        float pulse = 0.82F + 0.18F * (float) Math.sin(age * 0.24F);
        VertexConsumer aura = buffer.getBuffer(RenderType.entityTranslucentEmissive(WHITE_TEXTURE));

        renderContourFlames(poseStack, aura, age, pulse);
        renderMovementTrail(poseStack, aura, entity, age);
    }

    /**
     * Thirty-two narrow, wavy tongues rise around the feet, legs, torso, arms,
     * shoulders and head. Each strip faces outward from the body; the center
     * remains completely transparent so the skin stays visible.
     */
    private static void renderContourFlames(PoseStack poseStack, VertexConsumer consumer,
                                            float age, float pulse) {
        Matrix4f matrix = poseStack.last().pose();
        for (int i = 0; i < 32; i++) {
            float angle = (float) (Math.PI * 2.0D * i / 32.0D) + age * 0.0035F;
            float startY = -0.035F - (i % 8) * 0.185F;
            float height = 0.42F + (i % 5) * 0.105F
                    + 0.045F * (float) Math.sin(age * 0.08F + i * 1.7F);
            float width = 0.030F + (i % 4) * 0.006F;
            float phase = i * 1.73F;
            emitFlameTongue(consumer, matrix, angle, startY, height, width, phase, age, pulse, true);
            emitFlameTongue(consumer, matrix, angle, startY, height, width, phase, age, pulse, false);
        }
    }

    private static void emitFlameTongue(VertexConsumer consumer, Matrix4f matrix,
                                        float angle, float startY, float height,
                                        float baseWidth, float phase, float age,
                                        float pulse, boolean outerGlow) {
        final int segments = 7;
        float normalX = (float) Math.cos(angle);
        float normalZ = (float) Math.sin(angle);
        float tangentX = -normalZ;
        float tangentZ = normalX;

        for (int segment = 0; segment < segments; segment++) {
            float t0 = segment / (float) segments;
            float t1 = (segment + 1) / (float) segments;
            float y0 = startY - t0 * height;
            float y1 = startY - t1 * height;
            float wave0 = (float) Math.sin(age * 0.11F + phase + t0 * 6.2F) * 0.024F;
            float wave1 = (float) Math.sin(age * 0.11F + phase + t1 * 6.2F) * 0.024F;

            float[] radius0 = contourRadius(-y0);
            float[] radius1 = contourRadius(-y1);
            float x0 = normalX * (radius0[0] + wave0);
            float z0 = normalZ * (radius0[1] + wave0);
            float x1 = normalX * (radius1[0] + wave1);
            float z1 = normalZ * (radius1[1] + wave1);

            float taper0 = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * (0.06F + 0.88F * t0))), 0.72D);
            float taper1 = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * (0.06F + 0.88F * t1))), 0.72D);
            float half0 = baseWidth * taper0 * (outerGlow ? 2.35F : 0.78F);
            float half1 = baseWidth * taper1 * (outerGlow ? 2.35F : 0.78F);
            float fade0 = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * (0.04F + 0.92F * t0))), 0.65D);
            float fade1 = (float) Math.pow(Math.max(0.0F, Math.sin(Math.PI * (0.04F + 0.92F * t1))), 0.65D);

            int alphaBase = outerGlow ? 38 : 174;
            float alpha0 = alphaBase * fade0 * pulse;
            float alpha1 = alphaBase * fade1 * pulse;
            int red0 = (int) (128.0F + 127.0F * t0);
            int red1 = (int) (128.0F + 127.0F * t1);
            int blue0 = (int) (51.0F * t0);
            int blue1 = (int) (51.0F * t1);

            flameVertex(consumer, matrix, x0 - tangentX * half0, y0, z0 - tangentZ * half0,
                    0.0F, t0, red0, 0, blue0, alpha0, normalX, normalZ);
            flameVertex(consumer, matrix, x0 + tangentX * half0, y0, z0 + tangentZ * half0,
                    1.0F, t0, red0, 0, blue0, alpha0, normalX, normalZ);
            flameVertex(consumer, matrix, x1 + tangentX * half1, y1, z1 + tangentZ * half1,
                    1.0F, t1, red1, 0, blue1, alpha1, normalX, normalZ);
            flameVertex(consumer, matrix, x1 - tangentX * half1, y1, z1 - tangentZ * half1,
                    0.0F, t1, red1, 0, blue1, alpha1, normalX, normalZ);
        }
    }

    /**
     * Approximate the outside of a player model: slim legs below the waist,
     * broader torso/arms through the middle, then the head at the top.
     */
    private static float[] contourRadius(float height) {
        if (height < 0.48F) return new float[] {0.18F, 0.13F};
        if (height < 1.18F) return new float[] {0.285F, 0.18F};
        if (height < 1.48F) return new float[] {0.405F, 0.18F};
        return new float[] {0.19F, 0.155F};
    }

    private static void renderMovementTrail(PoseStack poseStack, VertexConsumer consumer,
                                            LivingEntity entity, float age) {
        double vx = entity.getDeltaMovement().x;
        double vz = entity.getDeltaMovement().z;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed < 0.025D) return;

        float bodyYaw = (float) Math.toRadians(entity.yBodyRot);
        float localVX = (float) (Math.cos(bodyYaw) * vx + Math.sin(bodyYaw) * vz);
        float localVZ = (float) (Math.sin(bodyYaw) * vx - Math.cos(bodyYaw) * vz);
        float localLength = (float) Math.sqrt(localVX * localVX + localVZ * localVZ);
        if (localLength < 1.0E-4F) return;
        localVX /= localLength;
        localVZ /= localLength;

        Matrix4f matrix = poseStack.last().pose();
        for (int i = 0; i < 4; i++) {
            float distance = 0.14F + i * 0.115F;
            float x = -localVX * distance + (float) Math.sin(age * 0.10F + i) * 0.022F;
            float z = -localVZ * distance;
            float y = -0.62F - i * 0.16F;
            float alpha = Math.max(0.0F, 0.27F - i * 0.065F);
            emitBillboardFlame(poseStack, consumer, x, y, z,
                    0.040F, 0.14F + i * 0.02F, alpha);
        }
    }

    private static void emitBillboardFlame(PoseStack poseStack, VertexConsumer consumer,
                                           float x, float y, float z,
                                           float halfWidth, float halfHeight, float alpha) {
        poseStack.pushPose();
        try {
            poseStack.translate(x, y, z);
            poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
            Matrix4f matrix = poseStack.last().pose();
            int rootAlpha = (int) (145.0F * alpha);
            int tipAlpha = 0;
            flameVertex(consumer, matrix, -halfWidth, halfHeight, 0.0F,
                    0.0F, 1.0F, 128, 0, 0, rootAlpha, 0.0F, 1.0F);
            flameVertex(consumer, matrix, halfWidth, halfHeight, 0.0F,
                    1.0F, 1.0F, 128, 0, 0, rootAlpha, 0.0F, 1.0F);
            flameVertex(consumer, matrix, halfWidth * 0.12F, -halfHeight, 0.0F,
                    1.0F, 0.0F, 255, 0, 51, tipAlpha, 0.0F, 1.0F);
            flameVertex(consumer, matrix, -halfWidth * 0.12F, -halfHeight, 0.0F,
                    0.0F, 0.0F, 255, 0, 51, tipAlpha, 0.0F, 1.0F);
        } finally {
            poseStack.popPose();
        }
    }

    private static void flameVertex(VertexConsumer consumer, Matrix4f matrix,
                                    float x, float y, float z, float u, float v,
                                    int red, int green, int blue, float alpha,
                                    float normalX, float normalZ) {
        consumer.vertex(matrix, x, y, z)
                .color(red / 255.0F, green / 255.0F, blue / 255.0F,
                        Math.max(0.0F, Math.min(255.0F, alpha)) / 255.0F)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(normalX, 0.0F, normalZ)
                .endVertex();
    }
}
