package net.hydra.jojomod.client.models.layers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.hydra.jojomod.client.ClientUtil;
import net.hydra.jojomod.entity.stand.KingCrimsonEntity;
import net.hydra.jojomod.event.powers.StandUser;
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
import org.joml.Matrix4f;

/**
 * Procedural red fire surrounding the local King Crimson stand.
 * Built from independent animated 3D flame ribbons, not a silhouette or repeating sprites.
 */
public final class TimeEraseAuraLayer<T extends LivingEntity, M extends EntityModel<T>>
        extends RenderLayer<T, M> {
    private static final ResourceLocation FLAME_TEXTURE =
            new ResourceLocation("roundabout", "textures/misc/flat_white.png");
    private static final RenderType FLAME_RENDER_TYPE = RenderType.entityTranslucent(FLAME_TEXTURE);
    private static final int FLAME_TONGUES = 18;
    private static final int VERTICAL_SEGMENTS = 10;
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public TimeEraseAuraLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount, float partialTicks,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null || entity.isInvisible()) return;

        boolean localKingCrimson = entity instanceof KingCrimsonEntity stand
                && stand.getUser() == localPlayer;
        boolean playerDuringTimeErase = entity == localPlayer
                && ClientUtil.isUsingTimeErase
                && !((StandUser) localPlayer).roundabout$hasStandOut();

        // In normal play the flame surrounds the summoned King Crimson.
        // If the stand is stored, Time Erase can still surround the local player.
        if (!localKingCrimson && !playerDuringTimeErase) return;

        float radius = localKingCrimson
                ? Math.max(0.72F, entity.getBbWidth() * 1.22F)
                : Math.max(0.48F, entity.getBbWidth() * 1.02F);
        float height = localKingCrimson
                ? Math.max(2.48F, entity.getBbHeight() * 1.18F)
                : Math.max(1.90F, entity.getBbHeight() * 1.06F);

        float age = entity.tickCount + partialTicks;
        float pulse = 0.92F + 0.08F * (float) Math.sin(age * 0.19F);

        poseStack.pushPose();
        try {
            poseStack.translate(0.0D, 0.20D, 0.0D);
            poseStack.mulPose(Axis.YP.rotationDegrees(age * 0.42F));
            Matrix4f matrix = poseStack.last().pose();
            VertexConsumer fire = buffer.getBuffer(FLAME_RENDER_TYPE);

            for (int i = 0; i < FLAME_TONGUES; i++) {
                float variation = noise(i * 17 + 3);
                float phase = i * 2.3999632F + variation * 0.72F;
                float angle = phase + age * (0.006F + variation * 0.004F);
                float tongueHeight = height * (0.66F + noise(i * 29 + 11) * 0.48F);
                float baseRadius = radius * (0.76F + noise(i * 37 + 5) * 0.32F);
                float baseWidth = radius * (0.16F + noise(i * 43 + 19) * 0.075F);
                float drift = (noise(i * 13 + 7) - 0.5F) * 0.30F;

                renderFlameTongue(fire, matrix, age, angle, phase, tongueHeight,
                        baseRadius, baseWidth, drift, height, pulse, false, 1.0F, 0.0F);

                // Selected tongues carry a thinner, hotter inner lick.
                if (i % 3 == 0) {
                    renderFlameTongue(fire, matrix, age, angle + 0.035F, phase + 1.4F,
                            tongueHeight * 0.78F, baseRadius - 0.045F,
                            baseWidth, drift * 0.72F, height, pulse,
                            true, 0.34F, -0.012F);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    private static void renderFlameTongue(VertexConsumer consumer, Matrix4f matrix,
                                          float age, float baseAngle, float phase,
                                          float tongueHeight, float baseRadius,
                                          float baseWidth, float drift, float totalHeight,
                                          float pulse, boolean inner,
                                          float widthScale, float radialOffset) {
        float baseY = -totalHeight * 0.48F;
        for (int segment = 0; segment < VERTICAL_SEGMENTS; segment++) {
            float t0 = segment / (float) VERTICAL_SEGMENTS;
            float t1 = (segment + 1) / (float) VERTICAL_SEGMENTS;

            float wave0 = (float) Math.sin(age * 0.13F + phase + t0 * 5.4F) * 0.12F
                    + (float) Math.sin(age * 0.075F + phase * 1.7F + t0 * 9.0F) * 0.045F;
            float wave1 = (float) Math.sin(age * 0.13F + phase + t1 * 5.4F) * 0.12F
                    + (float) Math.sin(age * 0.075F + phase * 1.7F + t1 * 9.0F) * 0.045F;
            float angle0 = baseAngle + wave0 + t0 * drift;
            float angle1 = baseAngle + wave1 + t1 * drift;
            float radial0 = baseRadius + radialOffset + t0 * totalHeight * 0.055F
                    + (float) Math.sin(age * 0.10F + phase + t0 * 6.0F) * 0.035F;
            float radial1 = baseRadius + radialOffset + t1 * totalHeight * 0.055F
                    + (float) Math.sin(age * 0.10F + phase + t1 * 6.0F) * 0.035F;

            float centerX0 = (float) Math.cos(angle0) * radial0
                    + (float) Math.sin(age * 0.16F + phase + t0 * 7.0F) * 0.035F;
            float centerZ0 = (float) Math.sin(angle0) * radial0;
            float centerX1 = (float) Math.cos(angle1) * radial1
                    + (float) Math.sin(age * 0.16F + phase + t1 * 7.0F) * 0.035F;
            float centerZ1 = (float) Math.sin(angle1) * radial1;

            float taper0 = (float) Math.pow(1.0F - t0, 0.88D);
            float taper1 = (float) Math.pow(1.0F - t1, 0.88D);
            float flicker = 0.88F + 0.12F * (float) Math.sin(age * 0.21F + phase);
            float halfWidth0 = baseWidth * widthScale * taper0 * flicker + 0.0015F;
            float halfWidth1 = baseWidth * widthScale * taper1 * flicker + 0.0008F;
            float tangentX0 = -(float) Math.sin(angle0);
            float tangentZ0 = (float) Math.cos(angle0);
            float tangentX1 = -(float) Math.sin(angle1);
            float tangentZ1 = (float) Math.cos(angle1);

            float y0 = baseY + t0 * tongueHeight;
            float y1 = baseY + t1 * tongueHeight;
            float leftX0 = centerX0 - tangentX0 * halfWidth0;
            float leftZ0 = centerZ0 - tangentZ0 * halfWidth0;
            float rightX0 = centerX0 + tangentX0 * halfWidth0;
            float rightZ0 = centerZ0 + tangentZ0 * halfWidth0;
            float leftX1 = centerX1 - tangentX1 * halfWidth1;
            float leftZ1 = centerZ1 - tangentZ1 * halfWidth1;
            float rightX1 = centerX1 + tangentX1 * halfWidth1;
            float rightZ1 = centerZ1 + tangentZ1 * halfWidth1;

            float alpha0 = (inner ? 0.40F : 0.34F) * taper0 * pulse;
            float alpha1 = (inner ? 0.40F : 0.34F) * taper1 * pulse;
            float green0 = inner ? 0.035F + t0 * 0.055F : 0.006F + t0 * 0.035F;
            float green1 = inner ? 0.035F + t1 * 0.055F : 0.006F + t1 * 0.035F;
            float blue = inner ? 0.028F : 0.012F;

            flameVertex(consumer, matrix, leftX0, y0, leftZ0, 0.0F, t0,
                    0.88F, green0, blue, alpha0);
            flameVertex(consumer, matrix, rightX0, y0, rightZ0, 1.0F, t0,
                    0.88F, green0, blue, alpha0);
            flameVertex(consumer, matrix, rightX1, y1, rightZ1, 1.0F, t1,
                    inner ? 1.0F : 0.95F, green1, blue, alpha1);
            flameVertex(consumer, matrix, leftX1, y1, leftZ1, 0.0F, t1,
                    inner ? 1.0F : 0.95F, green1, blue, alpha1);
        }
    }

    private static void flameVertex(VertexConsumer consumer, Matrix4f matrix,
                                    float x, float y, float z, float u, float v,
                                    float red, float green, float blue, float alpha) {
        float normalLength = (float) Math.sqrt(x * x + z * z);
        float nx = normalLength < 1.0E-5F ? 0.0F : x / normalLength;
        float nz = normalLength < 1.0E-5F ? 1.0F : z / normalLength;
        consumer.vertex(matrix, x, y, z)
                .color(red, green, blue, Math.max(0.0F, Math.min(0.48F, alpha)))
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(nx, 0.0F, nz)
                .endVertex();
    }

    private static float noise(int seed) {
        float value = (float) Math.sin(seed * 78.233F + 17.17F) * 43758.5453F;
        return value - (float) Math.floor(value);
    }
}
