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
 * Procedural red/pink fire around the local player, never around the stand model.
 * The ribbons form irregular flame tongues with open gaps and continuously moving edges.
 */
public final class TimeEraseAuraLayer<T extends LivingEntity, M extends EntityModel<T>>
        extends RenderLayer<T, M> {
    private static final ResourceLocation FLAME_TEXTURE =
            new ResourceLocation("roundabout", "textures/misc/flat_white.png");
    private static final RenderType FLAME_RENDER_TYPE = RenderType.entityTranslucent(FLAME_TEXTURE);
    private static final int FLAME_TONGUES = 20;
    private static final int VERTICAL_SEGMENTS = 12;
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;

    public TimeEraseAuraLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount, float partialTicks,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        LocalPlayer localPlayer = Minecraft.getInstance().player;
        if (localPlayer == null || entity != localPlayer || entity.isInvisible()) return;

        StandUser localStandUser = (StandUser) localPlayer;
        boolean kingCrimsonSummoned = localStandUser.roundabout$hasStandOut()
                && localStandUser.roundabout$getStand() instanceof KingCrimsonEntity;

        // The aura belongs to the player: while King Crimson is summoned, and
        // throughout Time Erase even if the stand has been stored.
        if (!kingCrimsonSummoned && !ClientUtil.isUsingTimeErase) return;

        float radius = Math.max(0.62F, entity.getBbWidth() * 1.14F);
        // Positive model-space Y moves down from the head pivot. This keeps the
        // aura over the torso/arms/legs instead of floating above the head.
        float bodyBandHeight = 1.16F;
        float baseY = 0.20F;

        float age = entity.tickCount + partialTicks;
        float pulse = 0.90F + 0.10F * (float) Math.sin(age * 0.17F);

        poseStack.pushPose();
        try {
            poseStack.translate(0.0D, 0.0D, 0.0D);
            poseStack.mulPose(Axis.YP.rotationDegrees(age * 0.34F));
            Matrix4f matrix = poseStack.last().pose();
            VertexConsumer fire = buffer.getBuffer(FLAME_RENDER_TYPE);

            for (int i = 0; i < FLAME_TONGUES; i++) {
                float variation = noise(i * 17 + 3);
                float phase = i * 2.3999632F + variation * 0.88F;
                float angle = phase + age * (0.008F + variation * 0.006F);
                float tongueHeight = bodyBandHeight * (0.62F + noise(i * 29 + 11) * 0.48F);
                float baseRadius = radius * (0.78F + noise(i * 37 + 5) * 0.38F);
                float baseWidth = radius * (0.15F + noise(i * 43 + 19) * 0.105F);
                float drift = (noise(i * 13 + 7) - 0.5F) * 0.42F;

                renderFlameTongue(fire, matrix, age, angle, phase, tongueHeight,
                        baseRadius, baseWidth, drift, bodyBandHeight, baseY,
                        pulse, false, 1.0F, 0.0F);

                // Randomly distributed hot pink inner tongues break up the silhouette.
                if (i % 3 == 0) {
                    renderFlameTongue(fire, matrix, age, angle + 0.045F, phase + 1.4F,
                            tongueHeight * 0.76F, baseRadius - 0.035F,
                            baseWidth, drift * 0.72F, bodyBandHeight, baseY,
                            pulse, true, 0.34F, -0.014F);
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
                                          float baseY, float pulse, boolean inner,
                                          float widthScale, float radialOffset) {
        for (int segment = 0; segment < VERTICAL_SEGMENTS; segment++) {
            float t0 = segment / (float) VERTICAL_SEGMENTS;
            float t1 = (segment + 1) / (float) VERTICAL_SEGMENTS;

            float wave0 = (float) Math.sin(age * 0.14F + phase + t0 * 5.8F) * 0.16F
                    + (float) Math.sin(age * 0.085F + phase * 1.7F + t0 * 9.7F) * 0.055F;
            float wave1 = (float) Math.sin(age * 0.14F + phase + t1 * 5.8F) * 0.16F
                    + (float) Math.sin(age * 0.085F + phase * 1.7F + t1 * 9.7F) * 0.055F;
            float angle0 = baseAngle + wave0 + t0 * drift;
            float angle1 = baseAngle + wave1 + t1 * drift;

            float radial0 = baseRadius + radialOffset + t0 * totalHeight * 0.055F
                    + (float) Math.sin(age * 0.11F + phase + t0 * 6.0F) * 0.045F;
            float radial1 = baseRadius + radialOffset + t1 * totalHeight * 0.055F
                    + (float) Math.sin(age * 0.11F + phase + t1 * 6.0F) * 0.045F;

            float centerX0 = (float) Math.cos(angle0) * radial0
                    + (float) Math.sin(age * 0.18F + phase + t0 * 7.0F) * 0.045F;
            float centerZ0 = (float) Math.sin(angle0) * radial0;
            float centerX1 = (float) Math.cos(angle1) * radial1
                    + (float) Math.sin(age * 0.18F + phase + t1 * 7.0F) * 0.045F;
            float centerZ1 = (float) Math.sin(angle1) * radial1;

            float taper0 = (float) Math.pow(1.0F - t0, 0.92D);
            float taper1 = (float) Math.pow(1.0F - t1, 0.92D);
            float flicker = 0.84F + 0.16F * (float) Math.sin(age * 0.23F + phase);
            float halfWidth0 = baseWidth * widthScale * taper0 * flicker + 0.001F;
            float halfWidth1 = baseWidth * widthScale * taper1 * flicker + 0.0006F;

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

            float alpha0 = (inner ? 0.43F : 0.36F) * taper0 * pulse;
            float alpha1 = (inner ? 0.43F : 0.36F) * taper1 * pulse;
            float green0 = inner ? 0.095F + t0 * 0.045F : 0.025F + t0 * 0.020F;
            float green1 = inner ? 0.095F + t1 * 0.045F : 0.025F + t1 * 0.020F;
            float blue0 = inner ? 0.27F : 0.14F;
            float blue1 = inner ? 0.27F : 0.14F;

            flameVertex(consumer, matrix, leftX0, y0, leftZ0, 0.0F, t0,
                    1.0F, green0, blue0, alpha0);
            flameVertex(consumer, matrix, rightX0, y0, rightZ0, 1.0F, t0,
                    1.0F, green0, blue0, alpha0);
            flameVertex(consumer, matrix, rightX1, y1, rightZ1, 1.0F, t1,
                    1.0F, green1, blue1, alpha1);
            flameVertex(consumer, matrix, leftX1, y1, leftZ1, 0.0F, t1,
                    1.0F, green1, blue1, alpha1);
        }
    }

    private static void flameVertex(VertexConsumer consumer, Matrix4f matrix,
                                    float x, float y, float z, float u, float v,
                                    float red, float green, float blue, float alpha) {
        float normalLength = (float) Math.sqrt(x * x + z * z);
        float nx = normalLength < 1.0E-5F ? 0.0F : x / normalLength;
        float nz = normalLength < 1.0E-5F ? 1.0F : z / normalLength;
        consumer.vertex(matrix, x, y, z)
                .color(red, green, blue, Math.max(0.0F, Math.min(0.50F, alpha)))
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
