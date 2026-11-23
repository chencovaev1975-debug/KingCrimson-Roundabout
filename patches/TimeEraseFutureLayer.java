package net.hydra.jojomod.client.models.layers;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.hydra.jojomod.Roundabout;
import net.hydra.jojomod.client.TimeEraseFractureEffect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Renders one animated, fullbright red future-image in front of each visible living entity. */
public final class TimeEraseFutureLayer<T extends LivingEntity, M extends EntityModel<T>> extends RenderLayer<T, M> {
    private static final ResourceLocation WHITE_TEXTURE =
            new ResourceLocation(Roundabout.MOD_ID, "textures/misc/flat_white.png");

    public TimeEraseFutureLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight,
                       T entity, float limbSwing, float limbSwingAmount, float partialTicks,
                       float ageInTicks, float netHeadYaw, float headPitch) {
        Vec3 predicted = TimeEraseFractureEffect.getPredictedFuturePosition(entity, partialTicks);
        if (predicted == null) return;

        Vec3 current = entity.getPosition(partialTicks);
        double dx = predicted.x - current.x;
        double dy = predicted.y - current.y;
        double dz = predicted.z - current.z;

        // LivingEntityRenderer has already rotated and flipped the model. Convert
        // the world-space future offset back into that model's local coordinates.
        float bodyYaw = Mth.rotLerp(partialTicks, entity.yBodyRotO, entity.yBodyRot);
        double yaw = Math.toRadians(bodyYaw);
        float localX = (float) (Math.cos(yaw) * dx + Math.sin(yaw) * dz);
        float localY = (float) -dy;
        float localZ = (float) (Math.sin(yaw) * dx - Math.cos(yaw) * dz);

        poseStack.pushPose();
        try {
            poseStack.translate(localX, localY, localZ);

            VertexConsumer halo = buffer.getBuffer(RenderType.entityTranslucentEmissive(WHITE_TEXTURE));
            poseStack.pushPose();
            try {
                poseStack.scale(1.055F, 1.035F, 1.055F);
                getParentModel().renderToBuffer(
                        poseStack, halo, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        0.86F, 0.0F, 0.035F, 0.18F
                );
            } finally {
                poseStack.popPose();
            }

            VertexConsumer silhouette = buffer.getBuffer(RenderType.entityTranslucentEmissive(WHITE_TEXTURE));
            getParentModel().renderToBuffer(
                    poseStack, silhouette, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    1.0F, 0.005F, 0.045F, 0.62F
            );
        } finally {
            poseStack.popPose();
        }
    }
}
