package net.hydra.jojomod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.hydra.jojomod.sound.ModSounds;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Client-only Time Erase surface pass.
 *
 * Only the visible top terrain within a 12-block radius is captured.
 * Before the break, the captured surface and a real alpha crack texture are shown.
 * At the break, the surface disappears and only thirteen large, textured rock
 * clusters remain. No world blocks or server-side state are modified.
 */
public final class TimeEraseFractureEffect {
    private static final int RADIUS = 12;
    private static final int BAND_COUNT = 3;
    private static final int BAND_RADIUS = RADIUS / BAND_COUNT;
    private static final int MAX_BLOCKS = 500;
    private static final int[] BAND_CAPS = {60, 170, 270};

    private static final int CRACKS_REACH_TICK = 34;
    private static final int SHATTER_TICK = 46; // 2.30 seconds
    private static final int CAMERA_SHAKE_END_TICK = 64;
    private static final int CUSTOM_THEME_TICK = 84; // 4.20 seconds
    private static final int BOULDER_COUNT = 13;

    private static final ResourceLocation CRACK_TEXTURE =
            new ResourceLocation("roundabout", "textures/misc/time_erase_cracks.png");
    private static final RenderType CRACK_RENDER_TYPE =
            RenderType.entityTranslucent(CRACK_TEXTURE);
    private static final float[][] TEXTURE_UV = {
            {0.0F, 0.0F}, {1.0F, 0.0F}, {1.0F, 1.0F}, {0.0F, 1.0F}
    };

    // Face order: UP, DOWN, NORTH, SOUTH, WEST, EAST. DOWN is never captured.
    private static final int[] DX = {0, 0, 0, 0, -1, 1};
    private static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private static final float[][] NORMAL = {
            {0, 1, 0}, {0, -1, 0}, {0, 0, -1},
            {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}
    };
    private static final float[][] U_AXIS = {
            {1, 0, 0}, {1, 0, 0}, {1, 0, 0},
            {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final float[][] V_AXIS = {
            {0, 0, -1}, {0, 0, 1}, {0, 1, 0},
            {0, 1, 0}, {0, 1, 0}, {0, 1, 0}
    };
    private static final float[][] FACE_ORIGIN = {
            {0.5F, 1.0F, 0.5F}, {0.5F, 0.0F, 0.5F},
            {0.5F, 0.5F, 0.0F}, {0.5F, 0.5F, 1.0F},
            {0.0F, 0.5F, 0.5F}, {1.0F, 0.5F, 0.5F}
    };

    private static final List<BlockRecord> BLOCKS = new ArrayList<>(MAX_BLOCKS);
    private static final List<Boulder> BOULDERS = new ArrayList<>(BOULDER_COUNT);

    private static boolean previousAbilityActive;
    private static boolean effectActive;
    private static boolean customThemePlayed;
    private static boolean bouldersCreated;
    private static int elapsedTicks;
    private static ClientLevel capturedLevel;
    private static double centerX;
    private static double centerY;
    private static double centerZ;
    private static float appliedCameraShake;

    private TimeEraseFractureEffect() {}

    public static void tick(LocalPlayer player, boolean abilityActive) {
        if (player == null) {
            reset();
            return;
        }

        ClientLevel level = (ClientLevel) player.level();
        if (capturedLevel != null && capturedLevel != level) {
            reset();
        }

        if (abilityActive && !previousAbilityActive) {
            begin(player, level);
        } else if (effectActive) {
            if (!ClientUtil.renderTimeErase()) {
                effectActive = false;
                BLOCKS.clear();
                BOULDERS.clear();
            } else {
                elapsedTicks++;

                if (elapsedTicks < BAND_COUNT && capturedLevel != null) {
                    captureBand(capturedLevel,
                            (int) Math.floor(centerX),
                            (int) Math.floor(centerY),
                            (int) Math.floor(centerZ),
                            elapsedTicks);
                }

                if (!bouldersCreated && elapsedTicks >= SHATTER_TICK) {
                    createBoulders();
                    bouldersCreated = true;
                }

                if (!customThemePlayed && elapsedTicks >= CUSTOM_THEME_TICK) {
                    Minecraft.getInstance().getSoundManager().play(
                            SimpleSoundInstance.forUI(ModSounds.DIAVOLO_THEME_EVENT, 1.0F)
                    );
                    customThemePlayed = true;
                }
            }
        }

        previousAbilityActive = abilityActive;
    }

    private static void begin(LocalPlayer player, ClientLevel level) {
        BLOCKS.clear();
        BOULDERS.clear();
        capturedLevel = level;
        centerX = player.getX();
        centerY = player.getY();
        centerZ = player.getZ();
        elapsedTicks = 0;
        effectActive = true;
        customThemePlayed = false;
        bouldersCreated = false;
        appliedCameraShake = 0.0F;

        captureBand(level,
                (int) Math.floor(centerX),
                (int) Math.floor(centerY),
                (int) Math.floor(centerZ),
                0);
    }

    public static void reset() {
        BLOCKS.clear();
        BOULDERS.clear();
        capturedLevel = null;
        previousAbilityActive = false;
        effectActive = false;
        customThemePlayed = false;
        bouldersCreated = false;
        elapsedTicks = 0;
        appliedCameraShake = 0.0F;
    }

    /**
     * Capture only the upper terrain face in one of three four-block rings.
     * The bounded scan is at most a 25x25 footprint, not 60x60xvertical.
     */
    private static void captureBand(ClientLevel level, int cx, int cy, int cz, int bandIndex) {
        if (bandIndex < 0 || bandIndex >= BAND_COUNT || BLOCKS.size() >= MAX_BLOCKS) return;

        int outerRadius = Math.min(RADIUS, (bandIndex + 1) * BAND_RADIUS);
        int innerRadius = bandIndex * BAND_RADIUS;
        int outerSquared = outerRadius * outerRadius;
        int innerSquared = innerRadius * innerRadius;
        int capacity = BAND_CAPS[bandIndex];
        Random random = new Random((((long) cx) << 32) ^ (((long) cz) << 1)
                ^ cy ^ (0x4B435246L + bandIndex * 0x9E3779B9L));
        List<BlockSnapshot> reservoir = new ArrayList<>(capacity);
        int seen = 0;

        for (int x = cx - outerRadius; x <= cx + outerRadius; x++) {
            int dx = x - cx;
            for (int z = cz - outerRadius; z <= cz + outerRadius; z++) {
                int dz = z - cz;
                int distanceSquared = dx * dx + dz * dz;
                if (distanceSquared > outerSquared
                        || (bandIndex > 0 && distanceSquared <= innerSquared)) {
                    continue;
                }

                int surfaceY = findTerrainSurfaceY(level, x, z);
                if (surfaceY == Integer.MIN_VALUE) continue;

                BlockPos pos = new BlockPos(x, surfaceY, z);
                BlockState state = level.getBlockState(pos);
                int faceMask = 1; // Top face only, plus exposed cliff walls.
                if (findTerrainSurfaceY(level, x, z - 1) < surfaceY) faceMask |= 1 << 2;
                if (findTerrainSurfaceY(level, x, z + 1) < surfaceY) faceMask |= 1 << 3;
                if (findTerrainSurfaceY(level, x - 1, z) < surfaceY) faceMask |= 1 << 4;
                if (findTerrainSurfaceY(level, x + 1, z) < surfaceY) faceMask |= 1 << 5;

                seen++;
                BlockSnapshot snapshot = new BlockSnapshot(x, surfaceY, z, state, faceMask);
                if (reservoir.size() < capacity) {
                    reservoir.add(snapshot);
                } else {
                    int replace = random.nextInt(seen);
                    if (replace < capacity) reservoir.set(replace, snapshot);
                }
            }
        }

        for (BlockSnapshot snapshot : reservoir) {
            if (BLOCKS.size() >= MAX_BLOCKS) break;
            BLOCKS.add(new BlockRecord(level, snapshot, cx, cz));
        }
    }

    private static int findTerrainSurfaceY(ClientLevel level, int x, int z) {
        int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        for (int y = topY; y >= level.getMinBuildHeight(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()
                    || !state.getFluidState().isEmpty()
                    || state.is(BlockTags.LEAVES)
                    || state.is(BlockTags.LOGS)
                    || !state.isSolidRender(level, pos)) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffer,
                              Camera camera, float partialTick) {
        if (!effectActive || !ClientUtil.renderTimeErase()) return;

        View view = new View(camera);

        if (elapsedTicks < SHATTER_TICK) {
            // The captured landscape is rendered only until the rocks separate.
            renderIntactBlocks(poseStack, buffer, view);
            buffer.endBatch();
            if (!BLOCKS.isEmpty()) {
                renderCracks(poseStack, buffer, view, partialTick);
            }
        } else {
            // Important: after the boulders emerge, NO captured terrain blocks
            // or crack overlays are drawn. The region behind them is fully empty.
            if (!BOULDERS.isEmpty()) {
                renderBoulders(poseStack, buffer, view, partialTick);
            }
        }
    }

    private static void renderIntactBlocks(PoseStack poseStack, MultiBufferSource buffer,
                                           View camera) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();

        for (BlockRecord record : BLOCKS) {
            if (!isInView(record.x + 0.5D, record.y + 0.5D, record.z + 0.5D, camera)) continue;

            poseStack.pushPose();
            poseStack.translate(
                    record.x - camera.x,
                    record.y - camera.y,
                    record.z - camera.z
            );
            dispatcher.renderSingleBlock(
                    record.state,
                    poseStack,
                    buffer,
                    record.packedLight,
                    OverlayTexture.NO_OVERLAY
            );
            poseStack.popPose();
        }
    }

    /**
     * Textured asphalt-like fissures, derived from the user's crack reference.
     * The texture has a transparent background and widened irregular grooves,
     * instead of the old thin black vector stripes.
     */
    private static void renderCracks(PoseStack poseStack,
                                     MultiBufferSource.BufferSource buffer,
                                     View camera, float partialTick) {
        VertexConsumer consumer = buffer.getBuffer(CRACK_RENDER_TYPE);
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = poseStack.last().pose();

        float now = elapsedTicks + partialTick;

        for (BlockRecord record : BLOCKS) {
            if (!isInView(record.x + 0.5D, record.y + 0.5D, record.z + 0.5D, camera)) continue;

            float reveal = now - record.crackStartTick;
            if (reveal <= 0.0F) continue;
            int alpha = Mth.clamp((int) (Mth.clamp(reveal / 3.5F, 0.0F, 1.0F) * 245.0F), 0, 245);

            for (int face = 0; face < 6; face++) {
                if ((record.faceMask & (1 << face)) == 0 || face == 1) continue;
                int rotation = (int) ((record.x * 31L + record.z * 17L + face * 13L) & 3L);
                float[] origin = FACE_ORIGIN[face];
                float[] normal = NORMAL[face];
                float[] axisU = U_AXIS[face];
                float[] axisV = V_AXIS[face];

                for (int corner = 0; corner < 4; corner++) {
                    float u = (corner == 1 || corner == 2) ? 1.0F : 0.0F;
                    float v = corner >= 2 ? 1.0F : 0.0F;
                    float x = origin[0] + axisU[0] * (u - 0.5F) + axisV[0] * (v - 0.5F) + normal[0] * 0.012F;
                    float y = origin[1] + axisU[1] * (u - 0.5F) + axisV[1] * (v - 0.5F) + normal[1] * 0.012F;
                    float z = origin[2] + axisU[2] * (u - 0.5F) + axisV[2] * (v - 0.5F) + normal[2] * 0.012F;
                    float[] uv = TEXTURE_UV[(corner + rotation) & 3];

                    consumer.vertex(matrix, record.x + x, record.y + y, record.z + z)
                            .color(255, 255, 255, alpha)
                            .uv(uv[0], uv[1])
                            .overlayCoords(OverlayTexture.NO_OVERLAY)
                            .uv2(record.packedLight)
                            .normal(normal[0], normal[1], normal[2])
                            .endVertex();
                }
            }
        }

        poseStack.popPose();
        buffer.endBatch(CRACK_RENDER_TYPE);
    }

    /**
     * Creates thirteen widely distributed large rock clusters. Each visible
     * boulder is built from 6-8 full, locally textured terrain pieces; it is
     * intentionally not one tiny shard per source block.
     */
    private static void createBoulders() {
        BOULDERS.clear();
        if (BLOCKS.isEmpty()) return;

        Random random = new Random(
                (((long) Math.floor(centerX)) << 32)
                        ^ ((long) Math.floor(centerZ))
                        ^ 0x4B43524F434BL
        );
        List<BlockRecord> centers = new ArrayList<>(BOULDER_COUNT);

        for (int attempt = 0; attempt < BOULDER_COUNT * 80
                && centers.size() < BOULDER_COUNT; attempt++) {
            BlockRecord candidate = BLOCKS.get(random.nextInt(BLOCKS.size()));
            boolean spaced = true;
            for (BlockRecord chosen : centers) {
                double dx = candidate.x - chosen.x;
                double dz = candidate.z - chosen.z;
                if (dx * dx + dz * dz < 10.0D) {
                    spaced = false;
                    break;
                }
            }
            if (spaced) centers.add(candidate);
        }

        // If terrain geometry is very irregular, fill remaining slots without duplicates.
        for (BlockRecord candidate : BLOCKS) {
            if (centers.size() >= BOULDER_COUNT) break;
            if (!centers.contains(candidate)) centers.add(candidate);
        }

        for (BlockRecord anchor : centers) {
            List<BoulderPiece> pieces = new ArrayList<>(8);
            int pieceCount = 6 + random.nextInt(3);

            for (int pieceIndex = 0; pieceIndex < pieceCount; pieceIndex++) {
                BlockRecord material = anchor;
                if (pieceIndex > 0) {
                    double bestDistance = Double.MAX_VALUE;
                    for (BlockRecord sample : BLOCKS) {
                        double dx = sample.x - anchor.x;
                        double dz = sample.z - anchor.z;
                        double distance = dx * dx + dz * dz;
                        if (distance < bestDistance && distance < 20.0D
                                && random.nextFloat() < 0.18F) {
                            bestDistance = distance;
                            material = sample;
                        }
                    }
                }

                float offsetX = 0.0F;
                float offsetY = 0.0F;
                float offsetZ = 0.0F;
                if (pieceIndex > 0) {
                    float angle = random.nextFloat() * (float) (Math.PI * 2.0D);
                    float spread = 0.55F + random.nextFloat() * 1.30F;
                    offsetX = (float) Math.cos(angle) * spread;
                    offsetZ = (float) Math.sin(angle) * spread;
                    offsetY = (random.nextFloat() - 0.35F) * 1.15F;
                }

                // 1.15-1.70 block-sized pieces create a single large 3D mass.
                float scale = 1.75F + random.nextFloat() * 0.65F;
                pieces.add(new BoulderPiece(
                        material.state,
                        material.packedLight,
                        offsetX,
                        offsetY,
                        offsetZ,
                        scale
                ));
            }

            double dx = anchor.x + 0.5D - centerX;
            double dz = anchor.z + 0.5D - centerZ;
            double length = Math.sqrt(dx * dx + dz * dz);
            float radialX = length > 0.001D ? (float) (dx / length) : 0.0F;
            float radialZ = length > 0.001D ? (float) (dz / length) : 0.0F;

            float velocityX = radialX * (0.008F + random.nextFloat() * 0.012F)
                    + (random.nextFloat() - 0.5F) * 0.012F;
            float velocityZ = radialZ * (0.008F + random.nextFloat() * 0.012F)
                    + (random.nextFloat() - 0.5F) * 0.012F;
            float velocityY = 0.020F + random.nextFloat() * 0.010F;

            BOULDERS.add(new Boulder(
                    anchor.x + 0.5F,
                    anchor.y + 1.0F,
                    anchor.z + 0.5F,
                    velocityX,
                    velocityY,
                    velocityZ,
                    random.nextInt(7),
                    random.nextFloat() * 360.0F,
                    (random.nextBoolean() ? 1.0F : -1.0F) * (0.22F + random.nextFloat() * 0.35F),
                    (random.nextBoolean() ? 1.0F : -1.0F) * (0.10F + random.nextFloat() * 0.20F),
                    pieces
            ));
        }
    }

    private static void renderBoulders(PoseStack poseStack,
                                       MultiBufferSource.BufferSource buffer,
                                       View camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();
        float now = elapsedTicks + partialTick;

        for (Boulder boulder : BOULDERS) {
            float age = now - SHATTER_TICK - boulder.spawnDelay;
            if (age < 0.0F) continue;

            double x = boulder.x + boulder.velocityX * age;
            double y = boulder.y + boulder.velocityY * age;
            double z = boulder.z + boulder.velocityZ * age;
            if (!isInView(x, y, z, camera)) continue;

            float bob = (float) Math.sin(age * 0.045F + boulder.phase) * 0.08F;

            poseStack.pushPose();
            poseStack.translate(x - camera.x, y - camera.y + bob, z - camera.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(
                    boulder.yaw + age * boulder.yawSpeed));
            poseStack.mulPose(Axis.XP.rotationDegrees(
                    boulder.pitch + age * boulder.pitchSpeed));

            for (BoulderPiece piece : boulder.pieces) {
                poseStack.pushPose();
                poseStack.translate(
                        piece.offsetX - piece.scale * 0.5F,
                        piece.offsetY - piece.scale * 0.5F,
                        piece.offsetZ - piece.scale * 0.5F
                );
                poseStack.scale(piece.scale, piece.scale, piece.scale);
                dispatcher.renderSingleBlock(
                        piece.state,
                        poseStack,
                        buffer,
                        piece.packedLight,
                        OverlayTexture.NO_OVERLAY
                );
                poseStack.popPose();
            }

            poseStack.popPose();
        }

        buffer.endBatch();
    }

    public static void applyCameraShake(PoseStack poseStack, float partialTick) {
        appliedCameraShake = cameraShake(partialTick);
        if (appliedCameraShake != 0.0F) {
            poseStack.translate(0.0D, appliedCameraShake, 0.0D);
        }
    }

    public static void removeCameraShake(PoseStack poseStack) {
        if (appliedCameraShake != 0.0F) {
            poseStack.translate(0.0D, -appliedCameraShake, 0.0D);
            appliedCameraShake = 0.0F;
        }
    }

    private static float cameraShake(float partialTick) {
        if (!effectActive) return 0.0F;
        float t = elapsedTicks + partialTick;
        if (t < SHATTER_TICK || t >= CAMERA_SHAKE_END_TICK) return 0.0F;
        float progress = (t - SHATTER_TICK)
                / (float) (CAMERA_SHAKE_END_TICK - SHATTER_TICK);
        float envelope = (float) Math.pow(1.0F - progress, 1.35D);
        return (float) Math.sin((t - SHATTER_TICK) * 2.15F) * 0.16F * envelope;
    }

    private static boolean isInView(double x, double y, double z, View view) {
        double dx = x - view.x;
        double dy = y - view.y;
        double dz = z - view.z;
        double lengthSquared = dx * dx + dy * dy + dz * dz;
        if (lengthSquared < 1.0E-5D) return true;
        double inverseLength = 1.0D / Math.sqrt(lengthSquared);
        double dot = (dx * view.lookX + dy * view.lookY + dz * view.lookZ) * inverseLength;
        return dot > -0.18D;
    }

    private static final class View {
        final double x, y, z;
        final double lookX, lookY, lookZ;

        View(Camera camera) {
            Vec3 position = camera.getPosition();
            x = position.x;
            y = position.y;
            z = position.z;

            double yaw = Math.toRadians(camera.getYRot());
            double pitch = Math.toRadians(camera.getXRot());
            double horizontal = Math.cos(pitch);
            lookX = -Math.sin(yaw) * horizontal;
            lookY = -Math.sin(pitch);
            lookZ = Math.cos(yaw) * horizontal;
        }
    }

    private static final class BlockSnapshot {
        final int x, y, z;
        final BlockState state;
        final int faceMask;

        BlockSnapshot(int x, int y, int z, BlockState state, int faceMask) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
            this.faceMask = faceMask;
        }
    }

    private static final class BlockRecord {
        final int x, y, z;
        final BlockState state;
        final int packedLight;
        final int faceMask;
        final float crackStartTick;

        BlockRecord(ClientLevel level, BlockSnapshot snapshot, int centerX, int centerZ) {
            x = snapshot.x;
            y = snapshot.y;
            z = snapshot.z;
            state = snapshot.state;
            faceMask = snapshot.faceMask;
            packedLight = LevelRenderer.getLightColor(level, new BlockPos(x, y, z));

            double dx = x + 0.5D - centerX;
            double dz = z + 0.5D - centerZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            crackStartTick = (float) (distance / RADIUS) * CRACKS_REACH_TICK;
        }
    }

    private static final class Boulder {
        final float x, y, z;
        final float velocityX, velocityY, velocityZ;
        final int spawnDelay;
        final float yaw, yawSpeed, pitchSpeed;
        final float pitch;
        final float phase;
        final List<BoulderPiece> pieces;

        Boulder(float x, float y, float z,
                float velocityX, float velocityY, float velocityZ,
                int spawnDelay, float yaw, float yawSpeed, float pitchSpeed,
                List<BoulderPiece> pieces) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.velocityZ = velocityZ;
            this.spawnDelay = spawnDelay;
            this.yaw = yaw;
            this.yawSpeed = yawSpeed;
            this.pitchSpeed = pitchSpeed;
            this.pitch = (yaw * 0.17F) % 35.0F;
            this.phase = yaw * 0.013F;
            this.pieces = pieces;
        }
    }

    private static final class BoulderPiece {
        final BlockState state;
        final int packedLight;
        final float offsetX, offsetY, offsetZ;
        final float scale;

        BoulderPiece(BlockState state, int packedLight,
                     float offsetX, float offsetY, float offsetZ, float scale) {
            this.state = state;
            this.packedLight = packedLight;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.offsetZ = offsetZ;
            this.scale = scale;
        }
    }
}
