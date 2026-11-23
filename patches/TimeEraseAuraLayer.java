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

/** Pulsing crimson-magenta shell and custom rising flame billboards for the activating player. */
public final class TimeEraseAuraLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private static final ResourceLocation AURA_TEXTURE =
            new ResourceLocation(Roundabout.MOD_ID, "textures/misc/diavolo_aura.png");
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

        float pulse = 0.5F + 0.5F * (float) Math.sin((entity.tickCount + partialTicks) * 0.34F);
        float shellAlpha = 0.23F + pulse * 0.10F;

        poseStack.pushPose();
        try {
            poseStack.scale(1.055F + pulse * 0.008F,
                    1.050F + pulse * 0.008F,
                    1.055F + pulse * 0.008F);

            VertexConsumer shell = buffer.getBuffer(RenderType.entityTranslucentEmissive(AURA_TEXTURE));
            getParentModel().renderToBuffer(
                    poseStack, shell, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    0.76F, 0.015F, 0.075F, shellAlpha
            );

            float scroll = -(entity.tickCount + partialTicks) * 0.0065F;
            VertexConsumer flame = buffer.getBuffer(RenderType.energySwirl(AURA_TEXTURE, 0.0F, scroll));
            getParentModel().renderToBuffer(
                    poseStack, flame, FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    1.0F, 0.015F, 0.18F, 0.34F + pulse * 0.12F
            );
        } finally {
            poseStack.popPose();
        }

        renderRisingFlames(poseStack, buffer, entity, partialTicks);
        renderMovementTrail(poseStack, buffer, entity, partialTicks);
    }

    private static void renderRisingFlames(PoseStack poseStack, MultiBufferSource buffer,
                                           LivingEntity entity, float partialTicks) {
        float age = entity.tickCount + partialTicks;
        VertexConsumer consumer = buffer.getBuffer(RenderType.energySwirl(
                AURA_TEXTURE, 0.0F, -age * 0.008F));
        for (int i = 0; i < 9; i++) {
            float phase = (age * 0.055F + i * 0.137F) % 1.0F;
            float angle = i * 2.39996F + age * 0.014F;
            float radiusX = 0.34F + (float) Math.sin(age * 0.09F + i) * 0.035F;
            float radiusZ = 0.27F + (float) Math.cos(age * 0.075F + i * 0.7F) * 0.025F;
            float x = (float) Math.cos(angle) * radiusX;
            float z = (float) Math.sin(angle) * radiusZ;
            float y = 0.88F - phase * 1.72F;
            float halfWidth = 0.065F + 0.04F * (1.0F - phase);
            float halfHeight = 0.18F + 0.11F * (float) Math.sin(Math.PI * phase);
            int alpha = 135 + (int) (75.0F * (1.0F - phase));

            poseStack.pushPose();
            try {
                poseStack.translate(x, y, z);
                poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
                emitFlameQuad(consumer, poseStack.last().pose(),
                        halfWidth, halfHeight, 245, 18, 58, alpha);
            } finally {
                poseStack.popPose();
            }
        }
    }

    private static void renderMovementTrail(PoseStack poseStack, MultiBufferSource buffer,
                                            LivingEntity entity, float partialTicks) {
        double vx = entity.getDeltaMovement().x;
        double vz = entity.getDeltaMovement().z;
        double speed = Math.sqrt(vx * vx + vz * vz);
        if (speed < 0.025D) return;

        float age = entity.tickCount + partialTicks;
        float bodyYaw = (float) Math.toRadians(entity.yBodyRot);
        float localVX = (float) (Math.cos(bodyYaw) * vx + Math.sin(bodyYaw) * vz);
        float localVZ = (float) (Math.sin(bodyYaw) * vx - Math.cos(bodyYaw) * vz);
        float localLength = (float) Math.sqrt(localVX * localVX + localVZ * localVZ);
        if (localLength < 1.0E-4F) return;
        localVX /= localLength;
        localVZ /= localLength;

        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucentEmissive(AURA_TEXTURE));
        for (int i = 0; i < 5; i++) {
            float distance = 0.18F + i * 0.095F;
            float x = -localVX * distance + (float) Math.sin(age * 0.14F + i) * 0.035F;
            float z = -localVZ * distance;
            float y = 0.55F - i * 0.055F;
            float alpha = Math.max(0.08F, 0.28F - i * 0.035F);

            poseStack.pushPose();
            try {
                poseStack.translate(x, y, z);
                poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());
                emitFlameQuad(consumer, poseStack.last().pose(),
                        0.035F, 0.18F + i * 0.008F, 220, 8, 45, (int) (alpha * 255.0F));
            } finally {
                poseStack.popPose();
            }
        }
    }

    private static void emitFlameQuad(VertexConsumer consumer, Matrix4f matrix,
                                      float halfWidth, float halfHeight,
                                      int red, int green, int blue, int alpha) {
        flameVertex(consumer, matrix, -halfWidth, halfHeight, 0.0F, 0.0F, 1.0F,
                red, green, blue, alpha);
        flameVertex(consumer, matrix, halfWidth, halfHeight, 0.0F, 1.0F, 1.0F,
                red, green, blue, alpha);
        flameVertex(consumer, matrix, halfWidth * 0.12F, -halfHeight, 0.0F, 1.0F, 0.0F,
                red, green, blue, alpha);
        flameVertex(consumer, matrix, -halfWidth * 0.12F, -halfHeight, 0.0F, 0.0F, 0.0F,
                red, green, blue, alpha);
    }

    private static void flameVertex(VertexConsumer consumer, Matrix4f matrix,
                                    float x, float y, float z, float u, float v,
                                    int red, int green, int blue, int alpha) {
        consumer.vertex(matrix, x, y, z)
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(FULL_BRIGHT)
                .normal(0.0F, 0.0F, 1.0F)
                .endVertex();
    }
}
