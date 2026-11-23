package net.hydra.jojomod.client.models.layers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
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
 * Animated red fire cocoon. Four translucent, crossed flame sheets surround the
 * player while the alpha-masked texture keeps the actual skin visible inside.
 */
public final class TimeEraseAuraLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private static final ResourceLocation AURA_TEXTURE =
            new ResourceLocation(Roundabout.MOD_ID, "textures/misc/diavolo_aura.png");
    private static final int FULL_BRIGHT = LightTexture.FULL_BRIGHT;
    private static final int PLANES = 4;
    private static final int VERTICAL_SEGMENTS = 22;

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
        float pulse = 0.92F + 0.08F * (float) Math.sin(age * 0.22F);
        float scroll = -age * 0.012F;

        // Vanilla energySwirl supplies the additive/emissive blend and scrolling UV.
        VertexConsumer fire = buffer.getBuffer(RenderType.energySwirl(AURA_TEXTURE, 0.0F, scroll));
        for (int plane = 0; plane < PLANES; plane++) {
            poseStack.pushPose();
            try {
                poseStack.translate(0.0D, 0.24D, 0.0D);
                poseStack.mulPose(Axis.YP.rotationDegrees(plane * 45.0F + age * 0.75F));
                renderFlamePlane(poseStack.last().pose(), fire, age, pulse, plane);
            } finally {
                poseStack.popPose();
            }
        }

        renderMovementTrail(poseStack, buffer, entity, age);
    }

    /**
     * A two-block-tall sheet with animated wavy edges. The texture is made of
     * separate flame tongues; its transparent gaps expose the player's skin.
     */
    private static void renderFlamePlane(Matrix4f matrix, VertexConsumer consumer,
                                         float age, float pulse, int plane) {
        float height = 1.94F;
        float halfWidth = 0.57F;
        float topY = -height * 0.5F;
        float phase = plane * 1.91F;

        for (int segment = 0; segment < VERTICAL_SEGMENTS; segment++) {
            float t0 = segment / (float) VERTICAL_SEGMENTS;
            float t1 = (segment + 1) / (float) VERTICAL_SEGMENTS;
            float y0 = topY + t0 * height;
            float y1 = topY + t1 * height;
            float wave0 = (float) Math.sin(age * 0.10F + phase + t0 * 7.0F) * 0.035F;
            float wave1 = (float) Math.sin(age * 0.10F + phase + t1 * 7.0F) * 0.035F;
            float width0 = halfWidth * (0.94F + 0.06F * (float) Math.sin(age * 0.06F + t0 * 5.0F + phase));
            float width1 = halfWidth * (0.94F + 0.06F * (float) Math.sin(age * 0.06F + t1 * 5.0F + phase));
            float alpha0 = 0.40F * pulse;
            float alpha1 = 0.40F * pulse;
            float v0 = t0;
            float v1 = t1;

            flameVertex(consumer, matrix, -width0 + wave0, y0, 0.0F, 0.0F, v0, alpha0);
            flameVertex(consumer, matrix, width0 + wave0, y0, 0.0F, 1.0F, v0, alpha0);
            flameVertex(consumer, matrix, width1 + wave1, y1, 0.0F, 1.0F, v1, alpha1);
            flameVertex(consumer, matrix, -width1 + wave1, y1, 0.0F, 0.0F, v1, alpha1);
        }
    }

    private static void renderMovementTrail(PoseStack poseStack, MultiBufferSource buffer,
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

        VertexConsumer trail = buffer.getBuffer(RenderType.energySwirl(
                AURA_TEXTURE, 0.0F, -age * 0.016F));
        for (int i = 0; i < 3; i++) {
            float distance = 0.16F + i * 0.16F;
            float x = -localVX * distance + (float) Math.sin(age * 0.12F + i) * 0.025F;
            float z = -localVZ * distance;
            poseStack.pushPose();
            try {
                poseStack.translate(x, 0.48F - i * 0.12F, z);
                poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
                Matrix4f matrix = poseStack.last().pose();
                float halfWidth = 0.065F;
                float halfHeight = 0.18F;
                float alpha = 0.24F - i * 0.055F;
                flameVertex(trail, matrix, -halfWidth, halfHeight, 0.0F, 0.0F, 0.0F, alpha);
                flameVertex(trail, matrix, halfWidth, halfHeight, 0.0F, 1.0F, 0.0F, alpha);
                flameVertex(trail, matrix, halfWidth * 0.15F, -halfHeight, 0.0F, 1.0F, 1.0F, 0.0F);
                flameVertex(trail, matrix, -halfWidth * 0.15F, -halfHeight, 0.0F, 0.0F, 1.0F, 0.0F);
            } finally {
                poseStack.popPose();
            }
        }
    }

    private static void flameVertex(VertexConsumer consumer, Matrix4f matrix,
                                    float x, float y, float z, float u, float v, float alpha) {
        consumer.vertex(matrix, x, y, z)
                .color(1.0F, 0.0F, 0.12F, Math.max(0.0F, Math.min(0.45F, alpha)))
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
