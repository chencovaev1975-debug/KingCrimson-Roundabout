package net.hydra.jojomod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.hydra.jojomod.Roundabout;
import net.hydra.jojomod.event.powers.StandUser;
import net.hydra.jojomod.sound.ModSounds;
import net.hydra.jojomod.stand.powers.PowersKingCrimson;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Independent, client-only second Time Erase sequence.
 *
 * B starts an ordered fracture of the ground, a synchronized stone eruption,
 * and a 14.884-second orbiting debris field. It never edits world blocks,
 * collision, entity positions, or server state.
 */
public final class TimeEraseSecondEffect {
    private static final long MAIN_DURATION_NANOS = 14_884_014_000L;

    private static final double SLAB_LAUNCH_SECONDS = 2.78D;
    private static final double MAIN_AUDIO_DELAY_SECONDS = 3.55D
    private static final double TRANSITION_SECONDS = 0.30D;
    private static final int SLAB_COUNT = 20;

    private static final ResourceLocation FLAT_WHITE_TEXTURE =
            new ResourceLocation(Roundabout.MOD_ID, "textures/misc/flat_white.png");
    private static final RenderType GROUND_CRACK_RENDER_TYPE =
            RenderType.entityTranslucent(FLAT_WHITE_TEXTURE);
    private static final RenderType SLAB_RENDER_TYPE =
            RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);

    private static final List<CrackBranch> CRACKS = new ArrayList<>(18);
    private static final List<Slab> SLABS = new ArrayList<>(SLAB_COUNT);

    private static boolean active;
    private static boolean slabsCreated;
    private static long startNanos;
    private static long mainAudioStartNanos;
    private static ClientLevel capturedLevel;
    private static double centerX;
    private static double centerY;
    private static double centerZ;
    private static float facingYaw;

    private static SimpleSoundInstance introSound;
    private static SimpleSoundInstance mainSound;

    private static double cameraShakeX;
    private static double cameraShakeY;
    private static double cameraShakeZ;

    private TimeEraseSecondEffect() {}

    public static boolean isActive() {
        return active;
    }

    /**
     * The B key is accepted only while King Crimson is the player's selected power.
     * Normal Time Erase remains a separate activation path.
     */
    public static boolean start(LocalPlayer player) {
        if (active || player == null || !player.isAlive() || player.isSpectator()) return false;
        if (ClientUtil.isUsingTimeErase) return false;
        if (!(((StandUser) player).roundabout$getStandPowers() instanceof PowersKingCrimson)) return false;
        if (!(player.level() instanceof ClientLevel level)) return false;

        active = true;
        slabsCreated = false;
        startNanos = System.nanoTime();
        mainAudioStartNanos = 0L;
        capturedLevel = level;
        centerX = player.getX();
        centerY = player.getY();
        centerZ = player.getZ();
        facingYaw = player.getYRot();

        CRACKS.clear();
        SLABS.clear();
        cameraShakeX = cameraShakeY = cameraShakeZ = 0.0D;

        createGroundCrackNetwork(level, player);

        // Use the existing Time Erase local audio isolation. The two new clips
        // are explicitly allowlisted by TimeEraseFractureEffect.
        TimeEraseFractureEffect.beginSecondTimeEraseAudioIsolation();

        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        introSound = SimpleSoundInstance.forUI(ModSounds.TIME_ERASE_SECOND_INTRO_EVENT, 1.0F);
        soundManager.play(introSound);
        return true;
    }

    /** Advance timers, smoke emission, and the two exact-duration sound events. */
    public static void tick(LocalPlayer player) {
        if (!active) return;
        if (player == null || player.level() != capturedLevel || !player.isAlive()) {
            finish();
            return;
        }

        // The orbit follows the player, while the initial fissure remains anchored
        // to the place where the sequence began.
        centerX = player.getX();
        centerY = player.getY();
        centerZ = player.getZ();

        double elapsed = elapsedSeconds();
        updateCrackSmoke(elapsed);

        if (!slabsCreated && elapsed >= SLAB_LAUNCH_SECONDS) {
            createSlabs();
            spawnSlabLaunchDust();
            slabsCreated = true;
        }

        if (mainAudioStartNanos == 0L && elapsed >= MAIN_AUDIO_DELAY_SECONDS && slabsCreated) {
            SoundManager soundManager = Minecraft.getInstance().getSoundManager();
            if (introSound != null) {
                soundManager.stop(introSound);
                introSound = null;
            }
            mainSound = SimpleSoundInstance.forUI(ModSounds.TIME_ERASE_SECOND_MAIN_EVENT, 1.0F);
            soundManager.play(mainSound);
            mainAudioStartNanos = System.nanoTime();
        }

        // The end transition is driven by the actual main clip duration, not a
        // separate arbitrary visual timer.
        if (mainAudioStartNanos != 0L
                && System.nanoTime() - mainAudioStartNanos >= MAIN_DURATION_NANOS) {
            finish();
        }
    }

    public static void stop() {
        if (active) finish();
    }

    private static void finish() {
        SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        if (introSound != null) {
            soundManager.stop(introSound);
            introSound = null;
        }
        if (mainSound != null) {
            soundManager.stop(mainSound);
            mainSound = null;
        }
        if (active) {
            TimeEraseFractureEffect.endSecondTimeEraseAudioIsolation();
        }

        active = false;
        slabsCreated = false;
        startNanos = 0L;
        mainAudioStartNanos = 0L;
        capturedLevel = null;
        CRACKS.clear();
        SLABS.clear();
        cameraShakeX = cameraShakeY = cameraShakeZ = 0.0D;
    }

    public static float getElapsedSeconds() {
        return (float) elapsedSeconds();
    }

    private static double elapsedSeconds() {
        return startNanos == 0L ? 0.0D
                : Math.max(0.0D, (System.nanoTime() - startNanos) / 1_000_000_000.0D);
    }

    /**
     * Reuse the exact existing screen-space Time Erase renderer.
     * Opening runs bottom-to-top; closing reverses the same transition as the
     * second audio reaches its real end.
     */
    public static float getTransitionProgress() {
        if (!active || startNanos == 0L) return -1.0F;
        double elapsed = elapsedSeconds();

        if (elapsed <= TRANSITION_SECONDS) {
            return (float) Mth.clamp(elapsed / TRANSITION_SECONDS, 0.0D, 1.0D);
        }

        if (mainAudioStartNanos != 0L) {
            double remaining = (MAIN_DURATION_NANOS
                    - (System.nanoTime() - mainAudioStartNanos)) / 1_000_000_000.0D;
            if (remaining >= 0.0D && remaining <= TRANSITION_SECONDS) {
                return (float) Mth.clamp(remaining / TRANSITION_SECONDS, 0.0D, 1.0D);
            }
        }
        return -1.0F;
    }

    public static float getTransitionFade() {
        return getTransitionProgress() >= 0.0F ? 1.0F : 0.0F;
    }

    /** Multi-axis shake follows the branching impact and the instant the slabs erupt. */
    public static void applyCameraShake(PoseStack poseStack, float partialTick) {
        cameraShakeX = cameraShakeY = cameraShakeZ = 0.0D;
        if (!active || startNanos == 0L) return;

        double t = elapsedSeconds() + partialTick / 20.0D;
        if (t < 1.20D || t > 3.55D) return;

        double rise = smoothstep((t - 1.20D) / 1.15D);
        double decay = 1.0D - smoothstep((t - 2.86D) / 0.69D);
        double envelope = rise * decay;
        double burst = t >= 2.72D ? 1.0D : 0.52D;
        double amplitude = 0.035D + 0.22D * envelope * burst;

        cameraShakeX = Math.sin(t * 69.0D + 0.4D) * amplitude * 0.62D;
        cameraShakeY = Math.sin(t * 83.0D + 1.1D) * amplitude;
        cameraShakeZ = Math.sin(t * 57.0D + 2.0D) * amplitude * 0.16D;
        poseStack.translate(cameraShakeX, cameraShakeY, cameraShakeZ);
    }

    public static void removeCameraShake(PoseStack poseStack) {
        if (cameraShakeX != 0.0D || cameraShakeY != 0.0D || cameraShakeZ != 0.0D) {
            poseStack.translate(-cameraShakeX, -cameraShakeY, -cameraShakeZ);
            cameraShakeX = cameraShakeY = cameraShakeZ = 0.0D;
        }
    }

    private static double smoothstep(double value) {
        double t = Mth.clamp(value, 0.0D, 1.0D);
        return t * t * (3.0D - 2.0D * t);
    }

    private static void createGroundCrackNetwork(ClientLevel level, LocalPlayer player) {
        Random random = new Random(player.tickCount * 31L + player.blockPosition().asLong());

        double yaw = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double leftX = -forwardZ;
        double leftZ = forwardX;

        double originX = player.getX();
        double originZ = player.getZ();

        // Phase 1: one slow, deliberate fissure runs straight ahead.
        double frontEndX = originX + forwardX * 8.2D;
        double frontEndZ = originZ + forwardZ * 8.2D;
        CrackBranch front = createJaggedGroundPath(level, random,
                originX, originZ, frontEndX, frontEndZ,
                8, 0.075D, 0.20D, 1.05D, 0.060F);
        CRACKS.add(front);

        // Phase 2: only after the first line has travelled forward, it splits
        // into two long branches sweeping to the left and right.
        double splitX = originX + forwardX * 3.15D;
        double splitZ = originZ + forwardZ * 3.15D;
        double leftEndX = splitX + leftX * 5.8D + forwardX * 1.6D;
        double leftEndZ = splitZ + leftZ * 5.8D + forwardZ * 1.6D;
        double rightEndX = splitX - leftX * 5.8D + forwardX * 1.6D;
        double rightEndZ = splitZ - leftZ * 5.8D + forwardZ * 1.6D;

        CrackBranch left = createJaggedGroundPath(level, random,
                splitX, splitZ, leftEndX, leftEndZ,
                7, 0.20D, 1.13D, 0.88D, 0.058F);
        CrackBranch right = createJaggedGroundPath(level, random,
                splitX, splitZ, rightEndX, rightEndZ,
                7, 0.20D, 1.13D, 0.88D, 0.058F);
        CRACKS.add(left);
        CRACKS.add(right);

        // Phase 3: irregular secondary branches spread from the three existing
        // fractures. They do not form a grid or repeat a tile pattern.
        addSecondaryBranch(level, random, front, 0.48F, -leftX, -leftZ, 4.0D, 1.68D);
        addSecondaryBranch(level, random, front, 0.69F, leftX, leftZ, 3.5D, 1.82D);
        addSecondaryBranch(level, random, left, 0.42F, forwardX, forwardZ, 3.4D, 1.78D);
        addSecondaryBranch(level, random, left, 0.72F, -forwardX, -forwardZ, 2.8D, 2.03D);
        addSecondaryBranch(level, random, right, 0.40F, forwardX, forwardZ, 3.3D, 1.76D);
        addSecondaryBranch(level, random, right, 0.73F, -forwardX, -forwardZ, 3.0D, 2.04D);

        // A few short, independent splits create the broken stone-web appearance.
        for (int i = 0; i < 6; i++) {
            CrackBranch parent = (i % 3 == 0) ? front : (i % 3 == 1 ? left : right);
            Vec3 branchStart = pointAlong(parent.points, 0.28F + random.nextFloat() * 0.55F);
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double length = 1.6D + random.nextDouble() * 2.4D;
            CrackBranch branch = createJaggedGroundPath(level, random,
                    branchStart.x, branchStart.z,
                    branchStart.x + Math.cos(angle) * length,
                    branchStart.z + Math.sin(angle) * length,
                    3 + random.nextInt(3), 0.22D, 2.02D + random.nextDouble() * 0.42D,
                    0.55D + random.nextDouble() * 0.28D, 0.040F);
            CRACKS.add(branch);
        }
    }

    private static void addSecondaryBranch(ClientLevel level, Random random, CrackBranch parent,
                                           float parentProgress, double directionX,
                                           double directionZ, double length, double startTime) {
        Vec3 start = pointAlong(parent.points, parentProgress);
        double spread = (random.nextDouble() - 0.5D) * 0.50D;
        double dx = directionX * Math.cos(spread) - directionZ * Math.sin(spread);
        double dz = directionX * Math.sin(spread) + directionZ * Math.cos(spread);
        CRACKS.add(createJaggedGroundPath(level, random,
                start.x, start.z,
                start.x + dx * length, start.z + dz * length,
                4 + random.nextInt(2), 0.18D, startTime,
                0.70D + random.nextDouble() * 0.32D, 0.044F));
    }

    private static CrackBranch createJaggedGroundPath(ClientLevel level, Random random,
                                                       double startX, double startZ,
                                                       double endX, double endZ,
                                                       int segments, double jitter,
                                                       double startTime, double duration,
                                                       float width) {
        List<Vec3> points = new ArrayList<>(segments + 1);
        double dx = endX - startX;
        double dz = endZ - startZ;
        double distance = Math.max(0.001D, Math.sqrt(dx * dx + dz * dz));
        double perpendicularX = -dz / distance;
        double perpendicularZ = dx / distance;

        for (int i = 0; i <= segments; i++) {
            double t = i / (double) segments;
            double offset = (i == 0 || i == segments)
                    ? 0.0D : (random.nextDouble() - 0.5D) * jitter;
            double x = startX + dx * t + perpendicularX * offset;
            double z = startZ + dz * t + perpendicularZ * offset;
            points.add(surfacePoint(level, x, z));
        }
        return new CrackBranch(points, startTime, duration, width);
    }

    private static Vec3 surfacePoint(ClientLevel level, double x, double z) {
        int blockX = Mth.floor(x);
        int blockZ = Mth.floor(z);
        int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
        return new Vec3(x, surfaceY + 0.012D, z);
    }

    private static Vec3 pointAlong(List<Vec3> points, float progress) {
        if (points.isEmpty()) return Vec3.ZERO;
        if (points.size() == 1) return points.get(0);
        float t = Mth.clamp(progress, 0.0F, 1.0F) * (points.size() - 1);
        int first = Math.min(points.size() - 2, Mth.floor(t));
        return points.get(first).lerp(points.get(first + 1), t - first);
    }

    private static void updateCrackSmoke(double elapsed) {
        if (capturedLevel == null) return;
        for (CrackBranch crack : CRACKS) {
            double progress = (elapsed - crack.startSeconds) / crack.durationSeconds;
            if (progress <= 0.0D) continue;
            progress = Math.min(1.0D, progress);

            int revealedSteps = (int) Math.floor(progress * crack.length / 0.78D);
            while (crack.lastSmokeStep < revealedSteps) {
                crack.lastSmokeStep++;
                double distance = Math.min(crack.length,
                        crack.lastSmokeStep * 0.78D + 0.20D);
                Vec3 point = pointAtDistance(crack.points, distance, crack.length);
                Random random = new Random(
                        startNanos ^ ((long) crack.lastSmokeStep * 0x9E3779B97F4A7C15L)
                );
                capturedLevel.addParticle(ParticleTypes.CLOUD,
                        point.x, point.y + 0.04D, point.z,
                        (random.nextDouble() - 0.5D) * 0.025D,
                        0.025D + random.nextDouble() * 0.035D,
                        (random.nextDouble() - 0.5D) * 0.025D);
                if ((crack.lastSmokeStep & 1) == 0) {
                    capturedLevel.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE,
                            point.x, point.y + 0.05D, point.z,
                            0.0D, 0.018D, 0.0D);
                }
            }
        }
    }

    private static Vec3 pointAtDistance(List<Vec3> points, double distance, double fullLength) {
        if (points.size() < 2 || fullLength <= 1.0E-5D) {
            return points.isEmpty() ? Vec3.ZERO : points.get(0);
        }
        double target = Mth.clamp(distance, 0.0D, fullLength);
        double traversed = 0.0D;
        for (int i = 0; i < points.size() - 1; i++) {
            Vec3 a = points.get(i);
            Vec3 b = points.get(i + 1);
            double segmentLength = a.distanceTo(b);
            if (traversed + segmentLength >= target) {
                return a.lerp(b, (target - traversed) / Math.max(1.0E-5D, segmentLength));
            }
            traversed += segmentLength;
        }
        return points.get(points.size() - 1);
    }

    private static void renderGroundCracks(PoseStack poseStack,
                                           MultiBufferSource.BufferSource buffer,
                                           Camera camera, double elapsed) {
        Vec3 cameraPosition = camera.getPosition();
        VertexConsumer consumer = buffer.getBuffer(GROUND_CRACK_RENDER_TYPE);
        Matrix4f matrix = poseStack.last().pose();

        for (CrackBranch crack : CRACKS) {
            double reveal = (elapsed - crack.startSeconds) / crack.durationSeconds;
            if (reveal <= 0.0D) continue;
            double visibleLength = crack.length * Math.min(1.0D, reveal);
            double traversed = 0.0D;

            for (int i = 0; i < crack.points.size() - 1; i++) {
                Vec3 a = crack.points.get(i);
                Vec3 b = crack.points.get(i + 1);
                double length = a.distanceTo(b);
                if (visibleLength <= traversed) break;

                double segmentFraction = Mth.clamp(
                        (visibleLength - traversed) / Math.max(1.0E-5D, length), 0.0D, 1.0D);
                Vec3 visibleEnd = a.lerp(b, segmentFraction);
                emitGroundCrackSegment(consumer, matrix, cameraPosition, a, visibleEnd,
                        crack.width);
                traversed += length;
            }
        }
        buffer.endBatch(GROUND_CRACK_RENDER_TYPE);
    }

    private static void emitGroundCrackSegment(VertexConsumer consumer, Matrix4f matrix,
                                               Vec3 camera, Vec3 start, Vec3 end,
                                               float width) {
        double dx = end.x - start.x;
        double dz = end.z - start.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0E-4D) return;

        double perpendicularX = -dz / length;
        double perpendicularZ = dx / length;
        float outer = width * 1.65F;
        float inner = width * 0.46F;
        float midY = (float) ((start.y + end.y) * 0.5D);

        // Uneven bevel, two dark sloped shoulders, then a narrow recessed core.
        emitGroundQuad(consumer, matrix, camera,
                start, end, perpendicularX, perpendicularZ, outer,
                midY + 0.012F, 62, 53, 48, 210);
        emitGroundQuad(consumer, matrix, camera,
                start, end, perpendicularX, perpendicularZ, inner,
                midY + 0.006F, 13, 11, 14, 245);
        emitGroundLip(consumer, matrix, camera, start, end,
                perpendicularX, perpendicularZ, outer * 0.90D,
                midY + 0.024F, 112, 98, 86, 155);
    }

    private static void emitGroundQuad(VertexConsumer consumer, Matrix4f matrix, Vec3 camera,
                                       Vec3 start, Vec3 end, double px, double pz,
                                       double halfWidth, float yOffset,
                                       int red, int green, int blue, int alpha) {
        float y0 = (float) (start.y - camera.y) + yOffset;
        float y1 = (float) (end.y - camera.y) + yOffset;
        float ax = (float) (start.x - camera.x - px * halfWidth);
        float az = (float) (start.z - camera.z - pz * halfWidth);
        float bx = (float) (start.x - camera.x + px * halfWidth);
        float bz = (float) (start.z - camera.z + pz * halfWidth);
        float cx = (float) (end.x - camera.x + px * halfWidth);
        float cz = (float) (end.z - camera.z + pz * halfWidth);
        float dx = (float) (end.x - camera.x - px * halfWidth);
        float dz = (float) (end.z - camera.z - pz * halfWidth);
        emitGroundVertex(consumer, matrix, ax, y0, az, 0.0F, 0.0F, red, green, blue, alpha);
        emitGroundVertex(consumer, matrix, bx, y0, bz, 1.0F, 0.0F, red, green, blue, alpha);
        emitGroundVertex(consumer, matrix, cx, y1, cz, 1.0F, 1.0F, red, green, blue, alpha);
        emitGroundVertex(consumer, matrix, dx, y1, dz, 0.0F, 1.0F, red, green, blue, alpha);
    }

    private static void emitGroundLip(VertexConsumer consumer, Matrix4f matrix, Vec3 camera,
                                      Vec3 start, Vec3 end, double px, double pz,
                                      double halfWidth, float yOffset,
                                      int red, int green, int blue, int alpha) {
        double thin = halfWidth * 0.16D;
        emitGroundQuad(consumer, matrix, camera, start, end,
                px, pz, halfWidth, yOffset, red, green, blue, alpha);
        emitGroundQuad(consumer, matrix, camera, start, end,
                px, pz, thin, yOffset + 0.001F, red / 2, green / 2, blue / 2, alpha);
    }

    private static void emitGroundVertex(VertexConsumer consumer, Matrix4f matrix,
                                         float x, float y, float z,
                                         float u, float v, int red, int green, int blue, int alpha) {
        consumer.vertex(matrix, x, y, z)
                .color(red, green, blue, alpha)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    private static void createSlabs() {
        SLABS.clear();
        Random random = new Random(startNanos ^ 0x4B435F534C414253L);
        for (int i = 0; i < SLAB_COUNT; i++) {
            double angle = Math.PI * 2.0D * i / SLAB_COUNT
                    + (random.nextDouble() - 0.5D) * 0.16D;
            double launchRadius = 2.0D + random.nextDouble() * 0.85D;
            double launchX = centerX + Math.cos(angle) * launchRadius;
            double launchZ = centerZ + Math.sin(angle) * launchRadius;
            double orbitRadius = 4.6D + random.nextDouble() * 3.0D;
            double orbitHeight = -0.65D + random.nextDouble() * 5.5D;
            double orbitSpeed = (random.nextBoolean() ? 1.0D : -1.0D)
                    * (1.15D + random.nextDouble() * 0.72D);

            int blockX = Mth.floor(launchX);
            int blockZ = Mth.floor(launchZ);
            double groundY = capturedLevel.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);

            SLABS.add(new Slab(
                    angle, launchRadius, orbitRadius, orbitHeight, orbitSpeed,
                    groundY, random.nextDouble() * Math.PI * 2.0D,
                    3.05F + random.nextFloat() * 0.62F,
                    random.nextFloat() * 360.0F,
                    random.nextFloat() * 360.0F,
                    random.nextFloat() * 360.0F,
                    46.0F + random.nextFloat() * 62.0F,
                    (random.nextBoolean() ? 1.0F : -1.0F) * (54.0F + random.nextFloat() * 70.0F),
                    32.0F + random.nextFloat() * 85.0F,
                    createSlabMesh(random)
            ));
        }
    }

    private static SlabMesh createSlabMesh(Random random) {
        int sides = 6 + random.nextInt(2);
        float halfX = 0.82F + random.nextFloat() * 0.20F;
        float halfZ = 0.82F + random.nextFloat() * 0.20F;
        float halfY = 0.20F + random.nextFloat() * 0.14F;
        float phase = random.nextFloat() * (float) (Math.PI * 2.0D);
        float ringTwist = (random.nextFloat() - 0.5F) * 0.13F;
        float leanX = (random.nextFloat() - 0.5F) * 0.10F;
        float leanZ = (random.nextFloat() - 0.5F) * 0.10F;

        float[][] vertices = new float[sides * 3 + 2][3];
        for (int ring = 0; ring < 3; ring++) {
            for (int side = 0; side < sides; side++) {
                float angle = phase + (float) (Math.PI * 2.0D * side / sides)
                        + (ring - 1) * ringTwist;
                float radial = 0.86F + random.nextFloat() * 0.26F;
                float corner = (float) Math.sin(side * 2.31F + phase) * 0.055F
                        + (random.nextFloat() - 0.5F) * 0.11F;
                float x = (float) Math.cos(angle) * halfX * (radial + corner);
                float z = (float) Math.sin(angle) * halfZ * (radial - corner * 0.42F);
                float y;
                if (ring == 0) {
                    y = -halfY * (0.72F + random.nextFloat() * 0.25F);
                } else if (ring == 1) {
                    y = (random.nextFloat() - 0.5F) * halfY * 0.32F;
                } else {
                    y = halfY * (0.68F + random.nextFloat() * 0.36F);
                }
                x += ring * leanX;
                z += ring * leanZ;
                vertices[ring * sides + side] = new float[] {x, y, z};
            }
        }

        int lowerCenter = sides * 3;
        int upperCenter = lowerCenter + 1;
        vertices[lowerCenter] = new float[] {leanX, -halfY * 0.75F, leanZ};
        vertices[upperCenter] = new float[] {leanX * 2.0F, halfY * 0.72F, leanZ * 2.0F};

        int[][] faces = new int[sides * 6][3];
        int face = 0;
        for (int side = 0; side < sides; side++) {
            int next = (side + 1) % sides;
            int bottomA = side;
            int bottomB = next;
            int middleA = sides + side;
            int middleB = sides + next;
            int topA = sides * 2 + side;
            int topB = sides * 2 + next;

            faces[face++] = new int[] {bottomA, bottomB, middleB};
            faces[face++] = new int[] {bottomA, middleB, middleA};
            faces[face++] = new int[] {middleA, middleB, topB};
            faces[face++] = new int[] {middleA, topB, topA};
            faces[face++] = new int[] {lowerCenter, bottomB, bottomA};
            faces[face++] = new int[] {upperCenter, topA, topB};
        }
        return new SlabMesh(vertices, faces);
    }

    private static void spawnSlabLaunchDust() {
        if (capturedLevel == null) return;
        Random random = new Random(startNanos ^ 0x534D4F4B455F4255L);
        for (Slab slab : SLABS) {
            double angle = slab.angle;
            double x = centerX + Math.cos(angle) * slab.launchRadius;
            double z = centerZ + Math.sin(angle) * slab.launchRadius;
            double y = slab.groundY + 0.05D;
            BlockPos groundPos = new BlockPos(Mth.floor(x), Mth.floor(y - 0.1D), Mth.floor(z));
            BlockState state = capturedLevel.getBlockState(groundPos);
            if (state.isAir()) state = Blocks.STONE.defaultBlockState();
            BlockParticleOption debris = new BlockParticleOption(ParticleTypes.BLOCK, state);

            for (int p = 0; p < 5; p++) {
                capturedLevel.addParticle(debris, x, y, z,
                        (random.nextDouble() - 0.5D) * 0.42D,
                        0.12D + random.nextDouble() * 0.48D,
                        (random.nextDouble() - 0.5D) * 0.42D);
            }
            for (int p = 0; p < 3; p++) {
                capturedLevel.addParticle(ParticleTypes.CLOUD, x, y + 0.08D, z,
                        (random.nextDouble() - 0.5D) * 0.06D,
                        0.035D + random.nextDouble() * 0.08D,
                        (random.nextDouble() - 0.5D) * 0.06D);
            }
        }
    }

    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffer,
                              Camera camera, float partialTick) {
        if (!active || capturedLevel == null) return;
        double elapsed = elapsedSeconds() + partialTick / 20.0D;
        renderGroundCracks(poseStack, buffer, camera, elapsed);
        if (slabsCreated) renderSlabs(poseStack, buffer, camera, elapsed);
    }

    private static void renderSlabs(PoseStack poseStack, MultiBufferSource.BufferSource buffer,
                                    Camera camera, double elapsed) {
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlasSprite stoneSprite = minecraft.getBlockRenderer()
                .getBlockModelShaper()
                .getBlockModel(Blocks.STONE.defaultBlockState())
                .getParticleIcon();
        VertexConsumer consumer = buffer.getBuffer(SLAB_RENDER_TYPE);
        Vec3 cameraPosition = camera.getPosition();

        double flightAge = Math.max(0.0D, elapsed - SLAB_LAUNCH_SECONDS);
        float riseProgress = (float) smoothstep(flightAge / 0.72D);
        double fade = 1.0D;
        if (mainAudioStartNanos != 0L) {
            double mainElapsed = (System.nanoTime() - mainAudioStartNanos) / 1_000_000_000.0D;
            fade = 1.0D - smoothstep((mainElapsed - (MAIN_DURATION_NANOS / 1_000_000_000.0D - 0.34D)) / 0.34D);
        }
        int alpha = (int) (Mth.clamp((float) fade, 0.0F, 1.0F) * 255.0F);

        for (Slab slab : SLABS) {
            double angle = slab.angle + elapsed * slab.orbitSpeed;
            double radius = Mth.lerp(riseProgress, slab.launchRadius, slab.orbitRadius);
            radius += Math.sin(elapsed * 3.0D + slab.hoverPhase) * 0.22D;
            double x = centerX + Math.cos(angle) * radius;
            double z = centerZ + Math.sin(angle) * radius;
            double targetY = centerY + slab.orbitHeight;
            double y = Mth.lerp(riseProgress, slab.groundY, targetY)
                    + Math.sin(flightAge * 4.6D + slab.hoverPhase) * 0.20D;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) continue;

            poseStack.pushPose();
            try {
                poseStack.translate(x - cameraPosition.x, y - cameraPosition.y, z - cameraPosition.z);
                poseStack.mulPose(Axis.YP.rotationDegrees(slab.yaw + (float) elapsed * slab.yawSpeed));
                poseStack.mulPose(Axis.XP.rotationDegrees(slab.pitch + (float) elapsed * slab.pitchSpeed));
                poseStack.mulPose(Axis.ZP.rotationDegrees(slab.roll + (float) elapsed * slab.rollSpeed));
                poseStack.scale(slab.scale, slab.scale, slab.scale);

                Matrix4f matrix = poseStack.last().pose();
                Matrix3f normalMatrix = poseStack.last().normal();
                float centerMeshX = 0.0F, centerMeshY = 0.0F, centerMeshZ = 0.0F;
                for (float[] vertex : slab.mesh.vertices) {
                    centerMeshX += vertex[0];
                    centerMeshY += vertex[1];
                    centerMeshZ += vertex[2];
                }
                centerMeshX /= slab.mesh.vertices.length;
                centerMeshY /= slab.mesh.vertices.length;
                centerMeshZ /= slab.mesh.vertices.length;

                for (int[] face : slab.mesh.faces) {
                    float[] a = slab.mesh.vertices[face[0]];
                    float[] b = slab.mesh.vertices[face[1]];
                    float[] c = slab.mesh.vertices[face[2]];

                    float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
                    float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
                    float nx = uy * vz - uz * vy;
                    float ny = uz * vx - ux * vz;
                    float nz = ux * vy - uy * vx;
                    float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (normalLength < 1.0E-5F) continue;
                    nx /= normalLength;
                    ny /= normalLength;
                    nz /= normalLength;

                    float faceX = (a[0] + b[0] + c[0]) / 3.0F - centerMeshX;
                    float faceY = (a[1] + b[1] + c[1]) / 3.0F - centerMeshY;
                    float faceZ = (a[2] + b[2] + c[2]) / 3.0F - centerMeshZ;
                    if (nx * faceX + ny * faceY + nz * faceZ < 0.0F) {
                        float[] swap = b;
                        b = c;
                        c = swap;
                        nx = -nx;
                        ny = -ny;
                        nz = -nz;
                    }

                    float diffuse = Math.max(0.0F, nx * -0.38F + ny * 0.84F + nz * 0.38F);
                    float shade = 0.22F + diffuse * 0.78F;
                    float facetVariation = 0.82F
                            + ((face[0] * 17 + face[1] * 11 + face[2] * 7) & 7) * 0.022F;
                    int color = (int) (235.0F * Math.min(1.0F, shade * facetVariation));
                    emitSlabTriangle(consumer, matrix, normalMatrix, stoneSprite,
                            a, b, c, color, alpha, nx, ny, nz);
                }
            } finally {
                poseStack.popPose();
            }
        }
        buffer.endBatch(SLAB_RENDER_TYPE);
    }

    private static void emitSlabTriangle(VertexConsumer consumer, Matrix4f matrix,
                                          Matrix3f normalMatrix, TextureAtlasSprite sprite,
                                          float[] a, float[] b, float[] c,
                                          int color, int alpha,
                                          float nx, float ny, float nz) {
        emitSlabVertex(consumer, matrix, normalMatrix, sprite,
                a, 1.0F, 1.0F, color, alpha, nx, ny, nz);
        emitSlabVertex(consumer, matrix, normalMatrix, sprite,
                b, 15.0F, 1.0F, color, alpha, nx, ny, nz);
        emitSlabVertex(consumer, matrix, normalMatrix, sprite,
                c, 8.0F, 15.0F, color, alpha, nx, ny, nz);
        emitSlabVertex(consumer, matrix, normalMatrix, sprite,
                c, 8.0F, 15.0F, color, alpha, nx, ny, nz);
    }

    private static void emitSlabVertex(VertexConsumer consumer, Matrix4f matrix,
                                       Matrix3f normalMatrix, TextureAtlasSprite sprite,
                                       float[] point, float u, float v,
                                       int color, int alpha,
                                       float nx, float ny, float nz) {
        consumer.vertex(matrix, point[0], point[1], point[2])
                .color(color, color, color, alpha)
                .uv(sprite.getU(u), sprite.getV(v))
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, nx, ny, nz)
                .endVertex();
    }

    private static final class CrackBranch {
        final List<Vec3> points;
        final double startSeconds;
        final double durationSeconds;
        final float width;
        final double length;
        int lastSmokeStep = -1;

        CrackBranch(List<Vec3> points, double startSeconds, double durationSeconds, float width) {
            this.points = points;
            this.startSeconds = startSeconds;
            this.durationSeconds = durationSeconds;
            this.width = width;
            double total = 0.0D;
            for (int i = 0; i < points.size() - 1; i++) {
                total += points.get(i).distanceTo(points.get(i + 1));
            }
            this.length = total;
        }
    }

    private static final class SlabMesh {
        final float[][] vertices;
        final int[][] faces;

        SlabMesh(float[][] vertices, int[][] faces) {
            this.vertices = vertices;
            this.faces = faces;
        }
    }

    private static final class Slab {
        final double angle;
        final double launchRadius;
        final double orbitRadius;
        final double orbitHeight;
        final double orbitSpeed;
        final double groundY;
        final double hoverPhase;
        final float scale;
        final float yaw, pitch, roll;
        final float yawSpeed, pitchSpeed, rollSpeed;
        final SlabMesh mesh;

        Slab(double angle, double launchRadius, double orbitRadius,
             double orbitHeight, double orbitSpeed, double groundY,
             double hoverPhase, float scale,
             float yaw, float pitch, float roll,
             float yawSpeed, float pitchSpeed, float rollSpeed,
             SlabMesh mesh) {
            this.angle = angle;
            this.launchRadius = launchRadius;
            this.orbitRadius = orbitRadius;
            this.orbitHeight = orbitHeight;
            this.orbitSpeed = orbitSpeed;
            this.groundY = groundY;
            this.hoverPhase = hoverPhase;
            this.scale = scale;
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.yawSpeed = yawSpeed;
            this.pitchSpeed = pitchSpeed;
            this.rollSpeed = rollSpeed;
            this.mesh = mesh;
        }
    }
}
