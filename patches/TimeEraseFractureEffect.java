package net.hydra.jojomod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.core.Direction;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.hydra.jojomod.sound.ModSounds;
import net.hydra.jojomod.entity.stand.StandEntity;
import net.hydra.jojomod.entity.KingCrimsonCloneEntity;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.WaterAnimal;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Client-only Time Erase scene snapshot.
 *
 * The visible block shell (terrain, trees, leaves and other block models) is
 * captured in a bounded 30-block radius. It is never changed on the server.
 * At the shatter moment the captured scene is hidden and irregular 3D stone chunks
 * erupt from individually randomized ground anchors around the player and fade out.
 */
public final class TimeEraseFractureEffect {
    private static final int RADIUS = 30;
    private static final int BAND_COUNT = 30;
    private static final int CAPTURE_BANDS_PER_TICK = 2;
    private static final int BAND_RADIUS = RADIUS / BAND_COUNT;
    private static final int MAX_BLOCKS = 9000;
    private static final int OBJECT_CAP_PER_BAND = 180;
    private static final int OBJECT_SCAN_ABOVE = 40;
    private static final int OBJECT_SCAN_BELOW = 12;

    // The first fractures open at exactly 1.60 seconds (32 client ticks),
    // then spread across the captured ground before the rocks erupt at 2.30 seconds.
    private static final int CRACK_START_TICK = 32;
    private static final int CRACK_GROW_TICKS = 14;
    private static final int CRACK_MESHES_PER_TICK = 260;
    private static final int SHATTER_TICK = 46;
    private static final int CAMERA_SHAKE_END_TICK = 64;
    private static final int CUSTOM_THEME_TICK = 84;
    private static final int ROCK_LIFETIME_TICKS = 50;
    private static final int ROCK_FADE_TICKS = 10;
    private static final int ROCK_EMERGENCE_TICKS = 7;
    // Dissolve the captured terrain in the same short burst as the rocks launch.
    private static final int GROUND_DISSOLVE_TICKS = 4;
    private static final int ROCK_MAX_SPAWN_DELAY_TICKS = 2;
    private static final double SLASH_DELAY_SECONDS = 1.08D;
    private static final double SLASH_TRAVEL_SECONDS = 0.16D;
    private static final double SLASH_FADE_SECONDS = 0.06D;
    private static final int FUTURE_LEAD_TICKS = 25;
    private static final int BIG_ROCK_COUNT = 22;
    private static final int MEDIUM_ROCK_COUNT = 26;
    private static final int SMALL_ROCK_COUNT = 52;

    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private static final float[][] TRIANGLE_UV = {
            {1.0F, 1.0F}, {15.0F, 1.0F}, {8.0F, 15.0F}
    };
    private static final RenderType ROCK_RENDER_TYPE =
            RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);
    private static final List<BlockRecord> BLOCKS = new ArrayList<>(MAX_BLOCKS);
    // Only real terrain-surface blocks may seed the upward rock eruption.
    private static final List<BlockRecord> GROUND_ANCHORS = new ArrayList<>(3000);
    // One world-space fracture graph is clipped into the surface columns it crosses.
    // This prevents a tiled crack pattern from being generated independently per block.
    private static final List<WorldCrackSegment> WORLD_CRACK_SEGMENTS = new ArrayList<>(320);
    // Per-column 3D trench meshes retain the existing recessed floors and raised lips.
    private static final List<GroundCrack> GROUND_CRACKS = new ArrayList<>(1200);
    private static final List<Rock> ROCKS =
            new ArrayList<>(BIG_ROCK_COUNT + MEDIUM_ROCK_COUNT + SMALL_ROCK_COUNT);
    // Generated meshes are immutable after creation and reused by seed.
    private static final Map<Long, RockMesh> ROCK_MESH_CACHE = new HashMap<>(128);
    private static final Map<Integer, PredictionSmoother> FUTURE_PREDICTIONS = new HashMap<>(64);
    private static final Map<Integer, Long> FUTURE_PARTICLE_TIMES = new HashMap<>(64);
    private static final long FUTURE_PARTICLE_INTERVAL_NANOS = 240_000_000L;

    private static boolean previousAbilityActive;
    private static boolean effectActive;
    private static boolean customThemePlayed;
    private static boolean rocksCreated;
    private static boolean groundCracksCreated;
    private static boolean crackNetworkCreated;
    private static int groundCrackBuildIndex;
    private static int elapsedTicks;
    private static long effectStartNanos;
    private static ClientLevel capturedLevel;
    private static double centerX;
    private static double centerY;
    private static double centerZ;
    private static float appliedCameraShake;
    private static SimpleSoundInstance themeInstance;

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

        // Enforce the sound lifetime directly from the current ability state.
        // stopTheme also stops the registered event by ID, so no queued copy can continue.
        if (!abilityActive) {
            stopTheme();
        }

        if (abilityActive && !previousAbilityActive) {
            begin(player, level);
        } else if (effectActive) {
            if (!ClientUtil.renderTimeErase()) {
                stopTheme();
                effectActive = false;
                BLOCKS.clear();
                GROUND_ANCHORS.clear();
                ROCKS.clear();
                GROUND_CRACKS.clear();
                WORLD_CRACK_SEGMENTS.clear();
                crackNetworkCreated = false;
                groundCracksCreated = false;
                groundCrackBuildIndex = 0;
                FUTURE_PREDICTIONS.clear();
                FUTURE_PARTICLE_TIMES.clear();
            } else {
                elapsedTicks++;

                // Scan two narrow radial bands per tick. The complete 30-block
                // snapshot is ready before the 1.05-second violet block flash,
                // while the work is still divided across multiple client ticks.
                if (capturedLevel != null && elapsedTicks > 0) {
                    int firstBand = 1 + (elapsedTicks - 1) * CAPTURE_BANDS_PER_TICK;
                    for (int bandOffset = 0; bandOffset < CAPTURE_BANDS_PER_TICK; bandOffset++) {
                        int bandIndex = firstBand + bandOffset;
                        if (bandIndex >= BAND_COUNT) break;
                        captureBand(capturedLevel,
                                (int) Math.floor(centerX),
                                (int) Math.floor(centerY),
                                (int) Math.floor(centerZ),
                                bandIndex);
                    }
                }

                // Wait until the full ground ring is captured, then create one
                // impact-centered fracture graph and clip it into surface columns.
                // Meshes are still built in batches, keeping frame-time spikes bounded.
                int captureTicks = (BAND_COUNT + CAPTURE_BANDS_PER_TICK - 1)
                        / CAPTURE_BANDS_PER_TICK;
                if (elapsedTicks >= captureTicks) {
                    if (!crackNetworkCreated) {
                        createWorldCrackNetwork();
                        crackNetworkCreated = true;
                    }
                    if (!groundCracksCreated) {
                        buildGroundCracksBatch(CRACK_MESHES_PER_TICK);
                        if (groundCrackBuildIndex >= GROUND_ANCHORS.size()) {
                            groundCracksCreated = true;
                        }
                    }
                }

                if (!rocksCreated && elapsedTicks >= SHATTER_TICK) {
                    createRocks();
                    rocksCreated = true;
                }

                // The theme is local to the activating client and cannot start
                // during the post-ability visual fade.
                if (abilityActive && !customThemePlayed && elapsedTicks >= CUSTOM_THEME_TICK) {
                    themeInstance = SimpleSoundInstance.forUI(ModSounds.DIAVOLO_THEME_EVENT, 1.0F);
                    Minecraft.getInstance().getSoundManager().play(themeInstance);
                    customThemePlayed = true;
                }
            }
        }

        previousAbilityActive = abilityActive;
    }

    private static void begin(LocalPlayer player, ClientLevel level) {
        stopTheme();
        BLOCKS.clear();
        GROUND_ANCHORS.clear();
        ROCKS.clear();
        GROUND_CRACKS.clear();
        WORLD_CRACK_SEGMENTS.clear();
        crackNetworkCreated = false;
        groundCrackBuildIndex = 0;
        capturedLevel = level;
        centerX = player.getX();
        centerY = player.getY();
        centerZ = player.getZ();

        elapsedTicks = 0;
        effectStartNanos = System.nanoTime();
        effectActive = true;
        customThemePlayed = false;
        rocksCreated = false;
        groundCracksCreated = false;
        groundCrackBuildIndex = 0;
        FUTURE_PREDICTIONS.clear();
        FUTURE_PARTICLE_TIMES.clear();
        appliedCameraShake = 0.0F;

        captureBand(level,
                (int) Math.floor(centerX),
                (int) Math.floor(centerY),
                (int) Math.floor(centerZ),
                0);
    }

    public static void reset() {
        stopTheme();
        BLOCKS.clear();
        GROUND_ANCHORS.clear();
        ROCKS.clear();
        GROUND_CRACKS.clear();
        WORLD_CRACK_SEGMENTS.clear();
        crackNetworkCreated = false;
        groundCrackBuildIndex = 0;
        capturedLevel = null;
        previousAbilityActive = false;
        effectActive = false;
        customThemePlayed = false;
        rocksCreated = false;
        groundCracksCreated = false;
        groundCrackBuildIndex = 0;
        elapsedTicks = 0;
        effectStartNanos = 0L;
        appliedCameraShake = 0.0F;
        FUTURE_PREDICTIONS.clear();
        FUTURE_PARTICLE_TIMES.clear();
    }

    private static void stopTheme() {
        // Stop by event id as well as by instance to catch any queued/restarted copy.
        Minecraft.getInstance().getSoundManager().stop(ModSounds.DIAVOLO_THEME_ID, null);
        if (themeInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(themeInstance);
            themeInstance = null;
        }
    }

    /**
     * Capture exposed block models in one one-block-wide radial ring. The scan
     * extends above the ground to include trees and structures, and a few blocks
     * below the surface to preserve exposed cliff faces. Buried underground mass
     * is intentionally excluded.
     */
    private static void captureBand(ClientLevel level, int cx, int cy, int cz, int bandIndex) {
        if (bandIndex < 0 || bandIndex >= BAND_COUNT || BLOCKS.size() >= MAX_BLOCKS) return;

        int outerRadius = Math.min(RADIUS, (bandIndex + 1) * BAND_RADIUS);
        int innerRadius = bandIndex * BAND_RADIUS;
        int outerSquared = outerRadius * outerRadius;
        int innerSquared = innerRadius * innerRadius;
        int objectCapacity = OBJECT_CAP_PER_BAND;

        // Ground columns are stored separately. Surface blocks can no longer
        // consume the quota reserved for trees and other visible objects.
        List<BlockSnapshot> surfaceSnapshots = new ArrayList<>();
        List<BlockSnapshot> treeSnapshots = new ArrayList<>(objectCapacity);
        List<BlockSnapshot> objectSnapshots = new ArrayList<>(objectCapacity);
        BlockPos.MutableBlockPos scanPos = new BlockPos.MutableBlockPos();

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

                scanPos.set(x, surfaceY, z);
                BlockState surfaceState = level.getBlockState(scanPos);
                if (!surfaceState.isAir()
                        && surfaceState.getFluidState().isEmpty()
                        && surfaceState.getRenderShape() != RenderShape.INVISIBLE) {
                    surfaceSnapshots.add(new BlockSnapshot(x, surfaceY, z, surfaceState));
                }

                int minY = Math.max(level.getMinBuildHeight(), surfaceY - OBJECT_SCAN_BELOW);
                int maxY = Math.min(level.getMaxBuildHeight() - 1, surfaceY + OBJECT_SCAN_ABOVE);

                for (int y = maxY; y >= minY; y--) {
                    if (y == surfaceY) continue;
                    scanPos.set(x, y, z);
                    BlockState state = level.getBlockState(scanPos);
                    if (state.isAir()
                            || !state.getFluidState().isEmpty()
                            || state.getRenderShape() == RenderShape.INVISIBLE) {
                        continue;
                    }

                    // Keep complete tree structures, including leaves enclosed
                    // by other leaves. Other blocks must have an exposed face.
                    boolean treePart = state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
                    if (treePart) {
                        if (treeSnapshots.size() < objectCapacity) {
                            treeSnapshots.add(new BlockSnapshot(x, y, z, state));
                        }
                    } else if (hasExposedFace(level, x, y, z)
                            && objectSnapshots.size() < objectCapacity) {
                        objectSnapshots.add(new BlockSnapshot(x, y, z, state));
                    }
                }
            }
        }

        for (BlockSnapshot snapshot : surfaceSnapshots) {
            if (BLOCKS.size() >= MAX_BLOCKS) return;
            BlockRecord groundAnchor = new BlockRecord(level, snapshot);
            BLOCKS.add(groundAnchor);
            GROUND_ANCHORS.add(groundAnchor);
        }

        // Trees are prioritized over incidental exposed blocks. We no longer
        // use random reservoir sampling, which previously removed many leaves
        // and log segments from the same tree.
        int remaining = Math.min(objectCapacity, MAX_BLOCKS - BLOCKS.size());
        int added = 0;
        for (BlockSnapshot snapshot : treeSnapshots) {
            if (added >= remaining) break;
            BLOCKS.add(new BlockRecord(level, snapshot));
            added++;
        }
        for (BlockSnapshot snapshot : objectSnapshots) {
            if (added >= remaining) break;
            BLOCKS.add(new BlockRecord(level, snapshot));
            added++;
        }
    }

    private static int findTerrainSurfaceY(ClientLevel level, int x, int z) {
        int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        for (int y = topY; y >= level.getMinBuildHeight(); y--) {
            BlockPos pos = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir()
                    || !state.getFluidState().isEmpty()
                    || state.getRenderShape() == RenderShape.INVISIBLE
                    || state.is(BlockTags.LEAVES)
                    || state.is(BlockTags.LOGS)
                    || !state.isSolidRender(level, pos)) {
                continue;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean hasExposedFace(ClientLevel level, int x, int y, int z) {
        BlockPos.MutableBlockPos neighborPos = new BlockPos.MutableBlockPos();
        for (int[] offset : NEIGHBORS) {
            neighborPos.set(x + offset[0], y + offset[1], z + offset[2]);
            BlockState neighbor = level.getBlockState(neighborPos);
            if (neighbor.isAir() || !neighbor.isSolidRender(level, neighborPos)) {
                return true;
            }
        }
        return false;
    }

    public static void render(PoseStack poseStack, MultiBufferSource.BufferSource buffer,
                              Camera camera, float partialTick) {
        if (!effectActive || !ClientUtil.isUsingTimeErase || !ClientUtil.renderTimeErase()) return;

        View view = new View(camera);
        float dissolveProgress = 0.0F;
        if (elapsedTicks < SHATTER_TICK) {
            renderCapturedScene(poseStack, buffer, view, 0.0F);
            buffer.endBatch();
        } else {
            // The terrain begins its fast four-tick dissolve on the same frame
            // that the rock fragments emerge from the fractured surface.
            dissolveProgress = Math.max(0.0F, Math.min(1.0F,
                    (elapsedTicks + partialTick - SHATTER_TICK) / (float) GROUND_DISSOLVE_TICKS));
            if (dissolveProgress < 1.0F) {
                renderCapturedScene(poseStack, buffer, view, dissolveProgress);
                buffer.endBatch();
            }
        }

        // 3D fracture channels grow outward from the player from 1.60s to 2.30s.
        // During shatter, each crack disappears with the same terrain column.
        if (groundCracksCreated && elapsedTicks >= CRACK_START_TICK) {
            renderGroundCracks(poseStack, buffer, view, partialTick, dissolveProgress);
        }
        if (elapsedTicks >= SHATTER_TICK && !ROCKS.isEmpty()) {
            renderRocks(poseStack, buffer, view, partialTick);
        }
    }

    /**
     * Stable per-block ordering makes the captured terrain break apart in a
     * spatially distributed dissolve rather than vanishing as one solid slab.
     */
    private static float getDissolveOrder(BlockRecord record) {
        int hash = record.x * 73_428_767 ^ record.y * 912_931 ^ record.z * 438_289;
        hash = (hash ^ (hash >>> 16)) * 0x45d9f3b;
        hash = (hash ^ (hash >>> 16)) * 0x45d9f3b;
        hash ^= hash >>> 16;
        return (hash & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }

    private static void renderCapturedScene(PoseStack poseStack, MultiBufferSource buffer,
                                            View camera, float dissolveProgress) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();

        for (BlockRecord record : BLOCKS) {
            if (!isInView(record.x + 0.5D, record.y + 0.5D, record.z + 0.5D, camera)) continue;
            if (dissolveProgress > 0.0F && getDissolveOrder(record) <= dissolveProgress) continue;

            poseStack.pushPose();
            try {
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
            } finally {
                poseStack.popPose();
            }
        }
    }

    /**
     * Creates one irregular, impact-centered fracture graph in world coordinates.
     * Main fractures radiate from the impact point; secondary and tertiary branches
     * split from existing fractures, like cracks propagating through broken ice.
     */
    private static void createWorldCrackNetwork() {
        WORLD_CRACK_SEGMENTS.clear();
        if (GROUND_ANCHORS.isEmpty()) return;

        long seed = ((long) Math.floor(centerX) * 0x9E3779B97F4A7C15L)
                ^ ((long) Math.floor(centerZ) * 0xC2B2AE3D27D4EB4FL)
                ^ 0x4B435F4943454352L;
        Random random = new Random(mixCrackSeed(seed));
        CrackPoint impact = new CrackPoint(
                (float) centerX + (random.nextFloat() - 0.5F) * 0.22F,
                (float) centerZ + (random.nextFloat() - 0.5F) * 0.22F
        );

        int primaryCount = 8 + random.nextInt(2);
        List<List<CrackPoint>> primaryPaths = new ArrayList<>(primaryCount);
        List<Float> primaryWidths = new ArrayList<>(primaryCount);
        List<Float> primaryLips = new ArrayList<>(primaryCount);

        // The first fractures form an uneven radial skeleton, not a block-by-block web.
        float angularOffset = random.nextFloat() * (float) (Math.PI * 2.0D);
        for (int i = 0; i < primaryCount; i++) {
            float angle = angularOffset
                    + (float) (Math.PI * 2.0D * i / primaryCount)
                    + (random.nextFloat() - 0.5F) * 0.24F;
            float length = 20.0F + random.nextFloat() * 9.0F;
            float halfWidth = 0.052F + random.nextFloat() * 0.022F;
            float lipHeight = 0.034F + random.nextFloat() * 0.024F;
            List<CrackPoint> path = createWorldCrackBranch(
                    impact, angle, length, 0.24F, random);
            if (path.size() < 2) continue;

            primaryPaths.add(path);
            primaryWidths.add(halfWidth);
            primaryLips.add(lipHeight);
            addWorldCrackPath(path, halfWidth, lipHeight, random);
        }

        // Branches split from different points along each main fracture. Their
        // angles, lengths and jaggedness vary, so the result never repeats as tiles.
        for (int pathIndex = 0; pathIndex < primaryPaths.size(); pathIndex++) {
            List<CrackPoint> parent = primaryPaths.get(pathIndex);
            if (parent.size() < 5) continue;

            List<Integer> candidates = new ArrayList<>();
            for (int i = 2; i < parent.size() - 1; i++) {
                CrackPoint point = parent.get(i);
                double dx = point.x - centerX;
                double dz = point.z - centerZ;
                if (Math.sqrt(dx * dx + dz * dz) < RADIUS - 5.0D) {
                    candidates.add(i);
                }
            }
            for (int i = candidates.size() - 1; i > 0; i--) {
                int swap = random.nextInt(i + 1);
                int value = candidates.get(i);
                candidates.set(i, candidates.get(swap));
                candidates.set(swap, value);
            }

            int branchCount = Math.min(3 + random.nextInt(3), candidates.size());
            for (int branchIndex = 0; branchIndex < branchCount; branchIndex++) {
                int pivotIndex = candidates.get(branchIndex);
                CrackPoint previous = parent.get(pivotIndex - 1);
                CrackPoint pivot = parent.get(pivotIndex);
                float tangent = (float) Math.atan2(
                        pivot.z - previous.z, pivot.x - previous.x);
                float side = random.nextBoolean() ? 1.0F : -1.0F;
                float branchAngle = tangent
                        + side * (0.48F + random.nextFloat() * 0.92F);
                float branchLength = 4.2F + random.nextFloat() * 7.6F;
                float branchWidth = primaryWidths.get(pathIndex)
                        * (0.62F + random.nextFloat() * 0.18F);
                float branchLip = primaryLips.get(pathIndex)
                        * (0.66F + random.nextFloat() * 0.25F);
                List<CrackPoint> branch = createWorldCrackBranch(
                        pivot, branchAngle, branchLength, 0.46F, random);
                if (branch.size() < 2) continue;
                addWorldCrackPath(branch, branchWidth, branchLip, random);

                // A smaller number of fine forks create natural, uneven fracture edges.
                if (branch.size() >= 4 && random.nextFloat() < 0.34F) {
                    int forkIndex = 1 + random.nextInt(branch.size() - 2);
                    CrackPoint forkPrevious = branch.get(forkIndex - 1);
                    CrackPoint fork = branch.get(forkIndex);
                    float forkTangent = (float) Math.atan2(
                            fork.z - forkPrevious.z, fork.x - forkPrevious.x);
                    float forkSide = random.nextBoolean() ? 1.0F : -1.0F;
                    float forkAngle = forkTangent
                            + forkSide * (0.58F + random.nextFloat() * 0.86F);
                    List<CrackPoint> fineFork = createWorldCrackBranch(
                            fork, forkAngle, 2.4F + random.nextFloat() * 3.8F,
                            0.56F, random);
                    addWorldCrackPath(fineFork,
                            branchWidth * (0.48F + random.nextFloat() * 0.16F),
                            branchLip * (0.66F + random.nextFloat() * 0.18F),
                            random);
                }
            }
        }
    }

    /**
     * Extends one jagged fracture in short, irregular steps. The small direction
     * changes create sharp bends while the overall direction remains coherent.
     */
    private static List<CrackPoint> createWorldCrackBranch(CrackPoint start,
                                                            float initialAngle,
                                                            float maxLength,
                                                            float angularChaos,
                                                            Random random) {
        List<CrackPoint> points = new ArrayList<>(12);
        points.add(start);
        float x = start.x;
        float z = start.z;
        float angle = initialAngle;
        float traveled = 0.0F;

        for (int guard = 0; guard < 40 && traveled < maxLength; guard++) {
            angle += (random.nextFloat() - 0.5F) * angularChaos;
            float step = Math.min(maxLength - traveled,
                    1.05F + random.nextFloat() * 1.35F);
            float lateral = (random.nextFloat() - 0.5F) * 0.34F;
            float nextX = x + (float) Math.cos(angle) * step
                    - (float) Math.sin(angle) * lateral;
            float nextZ = z + (float) Math.sin(angle) * step
                    + (float) Math.cos(angle) * lateral;

            double radialX = nextX - centerX;
            double radialZ = nextZ - centerZ;
            double radialLength = Math.sqrt(radialX * radialX + radialZ * radialZ);
            double maxRadius = RADIUS - 0.45D;
            if (radialLength > maxRadius) {
                double scale = maxRadius / radialLength;
                nextX = (float) (centerX + radialX * scale);
                nextZ = (float) (centerZ + radialZ * scale);
            }

            float actualX = nextX - x;
            float actualZ = nextZ - z;
            float actualLength = (float) Math.sqrt(
                    actualX * actualX + actualZ * actualZ);
            if (actualLength < 0.18F) break;

            points.add(new CrackPoint(nextX, nextZ));
            traveled += step;
            x = nextX;
            z = nextZ;
            if (radialLength >= maxRadius - 0.02D) break;
        }
        return points;
    }

    private static void addWorldCrackPath(List<CrackPoint> points,
                                          float halfWidth,
                                          float lipHeight,
                                          Random random) {
        for (int i = 0; i < points.size() - 1; i++) {
            CrackPoint start = points.get(i);
            CrackPoint end = points.get(i + 1);
            float dx = end.x - start.x;
            float dz = end.z - start.z;
            if (dx * dx + dz * dz < 0.012F) continue;
            WORLD_CRACK_SEGMENTS.add(new WorldCrackSegment(
                    start.x, start.z, end.x, end.z,
                    halfWidth * (0.84F + random.nextFloat() * 0.32F),
                    lipHeight * (0.78F + random.nextFloat() * 0.40F),
                    random.nextLong()
            ));
        }
    }

    /**
     * Builds only the pieces of the shared fracture graph that cross each captured
     * surface block. Blocks untouched by a fracture remain intact and unmarked.
     */
    private static void buildGroundCracksBatch(int maxBlocks) {
        if (capturedLevel == null || GROUND_ANCHORS.isEmpty()
                || WORLD_CRACK_SEGMENTS.isEmpty()) {
            groundCrackBuildIndex = GROUND_ANCHORS.size();
            return;
        }

        int endIndex = Math.min(GROUND_ANCHORS.size(), groundCrackBuildIndex + maxBlocks);
        BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        for (int recordIndex = groundCrackBuildIndex; recordIndex < endIndex; recordIndex++) {
            BlockRecord record = GROUND_ANCHORS.get(recordIndex);
            long seed = crackBlockSeed(record);
            CrackMeshBuilder builder = new CrackMeshBuilder();

            for (WorldCrackSegment segment : WORLD_CRACK_SEGMENTS) {
                if (Math.max(segment.startX, segment.endX) < record.x
                        || Math.min(segment.startX, segment.endX) > record.x + 1.0F
                        || Math.max(segment.startZ, segment.endZ) < record.z
                        || Math.min(segment.startZ, segment.endZ) > record.z + 1.0F) {
                    continue;
                }

                CrackPath clipped = clipWorldCrackSegment(segment, record);
                if (clipped == null) continue;
                Random segmentRandom = new Random(seed ^ segment.seed);
                addCrackPath(builder, record, clipped, segmentRandom);
            }

            if (builder.quadCount == 0) continue;

            BakedModel model = dispatcher.getBlockModelShaper().getBlockModel(record.state);
            List<BakedQuad> topQuads = model.getQuads(
                    record.state, Direction.UP, RandomSource.create(seed));
            TextureAtlasSprite sprite = topQuads.isEmpty()
                    ? model.getParticleIcon()
                    : topQuads.get(new Random(seed ^ 0xA0761D6478BD642FL)
                            .nextInt(topQuads.size())).getSprite();
            if (sprite == null) continue;
            GROUND_CRACKS.add(builder.build(record, sprite));
        }
        groundCrackBuildIndex = endIndex;
    }

    /** Liang-Barsky clipping keeps each global fracture continuous across block edges. */
    private static CrackPath clipWorldCrackSegment(WorldCrackSegment segment,
                                                    BlockRecord record) {
        double dx = segment.endX - segment.startX;
        double dz = segment.endZ - segment.startZ;
        double[] interval = {0.0D, 1.0D};
        if (!clipCrackBoundary(-dx, segment.startX - record.x, interval)
                || !clipCrackBoundary(dx, record.x + 1.0D - segment.startX, interval)
                || !clipCrackBoundary(-dz, segment.startZ - record.z, interval)
                || !clipCrackBoundary(dz, record.z + 1.0D - segment.startZ, interval)) {
            return null;
        }

        double tStart = interval[0];
        double tEnd = interval[1];
        double segmentLength = Math.sqrt(dx * dx + dz * dz);
        if ((tEnd - tStart) * segmentLength < 0.035D) return null;

        float startX = (float) Math.max(0.0D, Math.min(1.0D,
                segment.startX + dx * tStart - record.x));
        float startZ = (float) Math.max(0.0D, Math.min(1.0D,
                segment.startZ + dz * tStart - record.z));
        float endX = (float) Math.max(0.0D, Math.min(1.0D,
                segment.startX + dx * tEnd - record.x));
        float endZ = (float) Math.max(0.0D, Math.min(1.0D,
                segment.startZ + dz * tEnd - record.z));

        List<CrackPoint> clippedPoints = new ArrayList<>(2);
        clippedPoints.add(new CrackPoint(startX, startZ));
        clippedPoints.add(new CrackPoint(endX, endZ));
        return new CrackPath(clippedPoints, segment.halfWidth, segment.lipHeight);
    }

    private static boolean clipCrackBoundary(double p, double q, double[] interval) {
        if (Math.abs(p) < 1.0E-10D) return q >= 0.0D;
        double ratio = q / p;
        if (p < 0.0D) {
            if (ratio > interval[1]) return false;
            if (ratio > interval[0]) interval[0] = ratio;
        } else {
            if (ratio < interval[0]) return false;
            if (ratio < interval[1]) interval[1] = ratio;
        }
        return true;
    }

    private static long crackBlockSeed(BlockRecord record) {
        long value = ((long) record.x * 0x9E3779B97F4A7C15L)
                ^ ((long) record.y * 0xC2B2AE3D27D4EB4FL)
                ^ ((long) record.z * 0x165667B19E3779F9L)
                ^ 0x4B435F4652414354L;
        return mixCrackSeed(value);
    }

    private static long mixCrackSeed(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /**
     * A port is deterministic for the shared world-space edge, so the endpoint
     * on one block is exactly the endpoint on its neighbor.
     */
    private static void addCrackEdgePorts(List<CrackPoint> ports, BlockRecord record, int edge) {
        int boundaryX;
        int boundaryZ;
        boolean verticalEdge;
        switch (edge) {
            case 0 -> {
                boundaryX = record.x;
                boundaryZ = record.z;
                verticalEdge = true;
            }
            case 1 -> {
                boundaryX = record.x + 1;
                boundaryZ = record.z;
                verticalEdge = true;
            }
            case 2 -> {
                boundaryX = record.x;
                boundaryZ = record.z;
                verticalEdge = false;
            }
            default -> {
                boundaryX = record.x;
                boundaryZ = record.z + 1;
                verticalEdge = false;
            }
        }

        long edgeKey = ((long) boundaryX * 0x9E3779B97F4A7C15L)
                ^ ((long) boundaryZ * 0xC2B2AE3D27D4EB4FL)
                ^ (verticalEdge ? 0x165667B19E3779F9L : 0x85EBCA77C2B2AE63L);
        Random edgeRandom = new Random(mixCrackSeed(edgeKey));
        int count = edgeRandom.nextFloat() < 0.74F ? 1 : 0;
        if (edgeRandom.nextFloat() < 0.10F) count++;
        if (count == 2) {
            float first = 0.16F + edgeRandom.nextFloat() * 0.68F;
            float second = 0.16F + edgeRandom.nextFloat() * 0.68F;
            if (Math.abs(first - second) < 0.18F) {
                second = first < 0.58F ? first + 0.22F : first - 0.22F;
            }
            addCrackPort(ports, edge, Math.min(first, second));
            addCrackPort(ports, edge, Math.max(first, second));
        } else if (count == 1) {
            addCrackPort(ports, edge, 0.16F + edgeRandom.nextFloat() * 0.68F);
        }
    }

    private static void addCrackPort(List<CrackPoint> ports, int edge, float alongEdge) {
        switch (edge) {
            case 0 -> ports.add(new CrackPoint(0.0F, alongEdge));
            case 1 -> ports.add(new CrackPoint(1.0F, alongEdge));
            case 2 -> ports.add(new CrackPoint(alongEdge, 0.0F));
            default -> ports.add(new CrackPoint(alongEdge, 1.0F));
        }
    }

    private static CrackPoint randomBoundaryPoint(Random random) {
        float along = 0.12F + random.nextFloat() * 0.76F;
        return switch (random.nextInt(4)) {
            case 0 -> new CrackPoint(0.0F, along);
            case 1 -> new CrackPoint(1.0F, along);
            case 2 -> new CrackPoint(along, 0.0F);
            default -> new CrackPoint(along, 1.0F);
        };
    }

    private static CrackPath createWavyCrackPath(CrackPoint start, CrackPoint end,
                                                  Random random, float halfWidth,
                                                  float lipHeight) {
        List<CrackPoint> points = new ArrayList<>(6);
        points.add(start);
        float dx = end.x - start.x;
        float dz = end.z - start.z;
        float length = (float) Math.sqrt(dx * dx + dz * dz);
        if (length > 1.0E-4F) {
            float normalX = -dz / length;
            float normalZ = dx / length;
            int bends = 1 + random.nextInt(2);
            for (int i = 1; i <= bends; i++) {
                float t = i / (float) (bends + 1);
                float jitter = (random.nextFloat() - 0.5F) * 0.22F;
                float x = start.x + dx * t + normalX * jitter;
                float z = start.z + dz * t + normalZ * jitter;
                points.add(new CrackPoint(
                        Math.max(0.045F, Math.min(0.955F, x)),
                        Math.max(0.045F, Math.min(0.955F, z))
                ));
            }
        }
        points.add(end);
        return new CrackPath(points, halfWidth, lipHeight);
    }

    private static void addCrackPath(CrackMeshBuilder builder, BlockRecord record,
                                     CrackPath path, Random random) {
        for (int i = 0; i < path.points.size() - 1; i++) {
            CrackPoint start = path.points.get(i);
            CrackPoint end = path.points.get(i + 1);
            float dx = end.x - start.x;
            float dz = end.z - start.z;
            float length = (float) Math.sqrt(dx * dx + dz * dz);
            if (length < 0.025F) continue;

            float normalX = -dz / length;
            float normalZ = dx / length;
            float widthA = path.halfWidth * (0.84F + random.nextFloat() * 0.30F);
            float widthB = path.halfWidth * (0.84F + random.nextFloat() * 0.30F);
            float lipA = path.lipHeight * (0.74F + random.nextFloat() * 0.42F);
            float lipB = path.lipHeight * (0.74F + random.nextFloat() * 0.42F);

            // Shared boundary ports taper to a point, which joins the neighboring
            // block's matching crack without doubled, overlapping trench geometry.
            if (i == 0 && isCrackBoundaryPoint(start)) {
                widthA *= 0.16F;
                lipA *= 0.36F;
            }
            if (i == path.points.size() - 2 && isCrackBoundaryPoint(end)) {
                widthB *= 0.16F;
                lipB *= 0.36F;
            }

            float floorScale = 0.16F + random.nextFloat() * 0.08F;
            float floorA = widthA * floorScale;
            float floorB = widthB * floorScale;
            float floorYA = 1.0025F + random.nextFloat() * 0.0020F;
            float floorYB = 1.0025F + random.nextFloat() * 0.0020F;

            float[] outerLeftA = crackVertex(
                    start.x + normalX * widthA, 1.0F + lipA,
                    start.z + normalZ * widthA);
            float[] outerLeftB = crackVertex(
                    end.x + normalX * widthB, 1.0F + lipB,
                    end.z + normalZ * widthB);
            float[] floorLeftA = crackVertex(
                    start.x + normalX * floorA, floorYA,
                    start.z + normalZ * floorA);
            float[] floorLeftB = crackVertex(
                    end.x + normalX * floorB, floorYB,
                    end.z + normalZ * floorB);
            float[] floorRightA = crackVertex(
                    start.x - normalX * floorA, floorYA,
                    start.z - normalZ * floorA);
            float[] floorRightB = crackVertex(
                    end.x - normalX * floorB, floorYB,
                    end.z - normalZ * floorB);
            float[] outerRightA = crackVertex(
                    start.x - normalX * widthA, 1.0F + lipA,
                    start.z - normalZ * widthA);
            float[] outerRightB = crackVertex(
                    end.x - normalX * widthB, 1.0F + lipB,
                    end.z - normalZ * widthB);

            float revealLeft = crackQuadReveal(record, start, end, random);
            float revealFloor = Math.min(1.0F, revealLeft + random.nextFloat() * 0.025F);
            float revealRight = Math.min(1.0F, revealLeft + random.nextFloat() * 0.025F);
            int bevelColor = 104 + random.nextInt(45);
            int floorColor = 15 + random.nextInt(19);

            // The two sloped bevels and the dark recessed floor are separate 3D
            // quads. Their height difference creates a real trough-shaped mesh.
            builder.addQuad(outerLeftA, outerLeftB, floorLeftB, floorLeftA,
                    bevelColor, revealLeft);
            builder.addQuad(floorLeftA, floorLeftB, floorRightB, floorRightA,
                    floorColor, revealFloor);
            builder.addQuad(floorRightA, floorRightB, outerRightB, outerRightA,
                    bevelColor, revealRight);
        }
    }

    private static boolean isCrackBoundaryPoint(CrackPoint point) {
        return point.x <= 0.001F || point.x >= 0.999F
                || point.z <= 0.001F || point.z >= 0.999F;
    }

    private static float[] crackVertex(float x, float y, float z) {
        return new float[] {x, y, z};
    }

    private static float crackQuadReveal(BlockRecord record, CrackPoint start,
                                         CrackPoint end, Random random) {
        float x = record.x + (start.x + end.x) * 0.5F;
        float z = record.z + (start.z + end.z) * 0.5F;
        double dx = x - centerX;
        double dz = z - centerZ;
        float radial = (float) (Math.sqrt(dx * dx + dz * dz) / (double) RADIUS);
        float jitter = (random.nextFloat() - 0.5F) * 0.085F;
        return Math.max(0.0F, Math.min(1.0F, radial + jitter));
    }

    private static void renderGroundCracks(PoseStack poseStack,
                                           MultiBufferSource.BufferSource buffer,
                                           View camera, float partialTick,
                                           float dissolveProgress) {
        float growth = Math.max(0.0F, Math.min(1.0F,
                (elapsedTicks + partialTick - CRACK_START_TICK) / (float) CRACK_GROW_TICKS));
        if (growth <= 0.0F && elapsedTicks < CRACK_START_TICK) return;

        VertexConsumer consumer = buffer.getBuffer(ROCK_RENDER_TYPE);
        for (GroundCrack crack : GROUND_CRACKS) {
            BlockRecord record = crack.record;
            if (!isInView(record.x + 0.5D, record.y + 1.0D, record.z + 0.5D, camera)) continue;
            if (dissolveProgress > 0.0F && getDissolveOrder(record) <= dissolveProgress) continue;

            poseStack.pushPose();
            try {
                poseStack.translate(
                        record.x - camera.x,
                        record.y - camera.y,
                        record.z - camera.z
                );
                Matrix4f matrix = poseStack.last().pose();
                org.joml.Matrix3f normalMatrix = poseStack.last().normal();

                for (int quad = 0; quad < crack.quadCount; quad++) {
                    if (crack.reveal[quad] > growth) continue;
                    emitCrackQuad(consumer, matrix, normalMatrix, crack.sprite,
                            crack.positions, crack.uvs, quad, crack.colors[quad]);
                }
            } finally {
                poseStack.popPose();
            }
        }
        buffer.endBatch(ROCK_RENDER_TYPE);
    }

    private static void emitCrackQuad(VertexConsumer consumer,
                                      Matrix4f matrix,
                                      org.joml.Matrix3f normalMatrix,
                                      TextureAtlasSprite sprite,
                                      float[] positions, float[] uvs,
                                      int quad, int color) {
        int p = quad * 12;
        int t = quad * 8;
        float ax = positions[p], ay = positions[p + 1], az = positions[p + 2];
        float bx = positions[p + 3], by = positions[p + 4], bz = positions[p + 5];
        float cx = positions[p + 6], cy = positions[p + 7], cz = positions[p + 8];

        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;
        float nx = uy * vz - uz * vy;
        float ny = uz * vx - ux * vz;
        float nz = ux * vy - uy * vx;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (length < 1.0E-5F) return;
        nx /= length;
        ny /= length;
        nz /= length;

        float diffuse = Math.max(0.0F, nx * -0.38F + ny * 0.84F + nz * 0.38F);
        int shaded = Math.max(0, Math.min(255,
                (int) (color * (0.34F + diffuse * 0.66F))));

        emitCrackVertex(consumer, matrix, normalMatrix, sprite,
                ax, ay, az, uvs[t], uvs[t + 1], shaded, nx, ny, nz);
        emitCrackVertex(consumer, matrix, normalMatrix, sprite,
                bx, by, bz, uvs[t + 2], uvs[t + 3], shaded, nx, ny, nz);
        emitCrackVertex(consumer, matrix, normalMatrix, sprite,
                cx, cy, cz, uvs[t + 4], uvs[t + 5], shaded, nx, ny, nz);
        emitCrackVertex(consumer, matrix, normalMatrix, sprite,
                positions[p + 9], positions[p + 10], positions[p + 11],
                uvs[t + 6], uvs[t + 7], shaded, nx, ny, nz);
    }

    private static void emitCrackVertex(VertexConsumer consumer,
                                        Matrix4f matrix,
                                        org.joml.Matrix3f normalMatrix,
                                        TextureAtlasSprite sprite,
                                        float x, float y, float z,
                                        float u, float v, int color,
                                        float nx, float ny, float nz) {
        consumer.vertex(matrix, x, y, z)
                .color(color, color, color, 255)
                .uv(sprite.getU(u), sprite.getV(v))
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, nx, ny, nz)
                .endVertex();
    }

    /**
     * Twenty-two large wall plates/beams, twenty-six medium masonry chunks and
     * fifty-two small sharp chips. Existing size and motion profiles are unchanged.
     */
    /**
     * Medium debris forms the inner ring (5–7 blocks), large slabs sit
     * farther out (8.5–11.5 blocks), and small chips are sampled near
     * those chosen anchors as secondary fragments.
     */
    private static void createRocks() {
        ROCKS.clear();
        if (GROUND_ANCHORS.isEmpty()) return;

        Random random = new Random(
                (((long) Math.floor(centerX)) << 32)
                        ^ ((long) Math.floor(centerZ))
                        ^ 0x4B43524F434BL
        );
        List<BlockRecord> usedAnchors = new ArrayList<>(
                BIG_ROCK_COUNT + MEDIUM_ROCK_COUNT + SMALL_ROCK_COUNT
        );
        List<BlockRecord> primaryAnchors = new ArrayList<>(BIG_ROCK_COUNT + MEDIUM_ROCK_COUNT);

        // The medium ring is deliberately selected first so it owns the closest
        // ground positions around the player.
        for (int i = 0; i < MEDIUM_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchorWithReuse(random, usedAnchors, 1.35D, 5.0D, 7.0D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            primaryAnchors.add(anchor);
            addRock(anchor, random,
                    1.20F + random.nextFloat() * 0.45F,
                    1.2F + random.nextFloat() * 1.0F,
                    ROCK_EMERGENCE_TICKS,
                    1);
        }

        // The large plates sit just beyond the medium ring.
        for (int i = 0; i < BIG_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchorWithReuse(random, usedAnchors, 2.0D, 8.5D, 11.5D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            primaryAnchors.add(anchor);
            addRock(anchor, random,
                    2.70F + random.nextFloat() * 0.50F,
                    2.5F + random.nextFloat() * 1.5F,
                    ROCK_EMERGENCE_TICKS,
                    0);
        }

        // Fine fragments are not gathered into a separate inner pile. They are
        // picked from ground positions close to medium and large anchor clusters.
        List<BlockRecord> smallAnchors = new ArrayList<>(SMALL_ROCK_COUNT);
        for (int i = 0; i < SMALL_ROCK_COUNT; i++) {
            BlockRecord anchor = pickSecondaryAnchor(random, primaryAnchors, smallAnchors);
            if (anchor == null) break;
            smallAnchors.add(anchor);
            addRock(anchor, random,
                    0.28F + random.nextFloat() * 0.24F,
                    0.25F + random.nextFloat() * 0.75F,
                    ROCK_EMERGENCE_TICKS,
                    2);
        }

        // A restrained burst of real block fragments accompanies the terrain dissolve.
        spawnTerrainDissolveParticles(random);
    }

    /**
     * Use distinct surface blocks first. If a tight ring has fewer ground
     * columns than requested rocks, reuse an eligible column; addRock gives
     * repeated anchors a small in-cell offset so their launch points differ.
     */
    private static BlockRecord pickAnchorWithReuse(Random random, List<BlockRecord> used,
                                                    double minSpacing, double minRadius,
                                                    double maxRadius) {
        BlockRecord anchor = pickAnchor(random, used, minSpacing, minRadius, maxRadius);
        if (anchor != null) return anchor;

        for (int attempt = 0; attempt < Math.max(400, GROUND_ANCHORS.size() * 2); attempt++) {
            BlockRecord candidate = GROUND_ANCHORS.get(random.nextInt(GROUND_ANCHORS.size()));
            if (isSafeVisibleAnchor(candidate, minRadius, maxRadius)) return candidate;
        }
        return null;
    }

    /**
     * Select a secondary chip anchor 0.9–2.8 blocks from a medium or large
     * anchor. Chips are also spaced from one another to avoid a separate heap.
     */
    private static BlockRecord pickSecondaryAnchor(Random random,
                                                   List<BlockRecord> primaryAnchors,
                                                   List<BlockRecord> selectedSmallAnchors) {
        double[] spacingFactors = {1.0D, 0.68D, 0.36D, 0.0D};
        for (double spacingFactor : spacingFactors) {
            double spacingSquared = 0.82D * 0.82D * spacingFactor * spacingFactor;

            for (int attempt = 0; attempt < Math.max(900, GROUND_ANCHORS.size() * 2); attempt++) {
                BlockRecord candidate = GROUND_ANCHORS.get(random.nextInt(GROUND_ANCHORS.size()));
                if (primaryAnchors.contains(candidate) || selectedSmallAnchors.contains(candidate)) {
                    continue;
                }

                double nearestPrimarySquared = Double.MAX_VALUE;
                for (BlockRecord primary : primaryAnchors) {
                    double dx = candidate.x - primary.x;
                    double dz = candidate.z - primary.z;
                    nearestPrimarySquared = Math.min(nearestPrimarySquared, dx * dx + dz * dz);
                }
                if (nearestPrimarySquared < 0.9D * 0.9D
                        || nearestPrimarySquared > 2.8D * 2.8D) {
                    continue;
                }

                boolean spaced = true;
                for (BlockRecord previous : selectedSmallAnchors) {
                    double dx = candidate.x - previous.x;
                    double dz = candidate.z - previous.z;
                    if (dx * dx + dz * dz < spacingSquared) {
                        spaced = false;
                        break;
                    }
                }
                if (spaced) return candidate;
            }
        }

        // On narrow terrain, keep the requested number of chips by relaxing only
        // chip-to-chip spacing; the requirement to stay near a primary chunk remains.
        for (int attempt = 0; attempt < Math.max(1200, GROUND_ANCHORS.size() * 3); attempt++) {
            BlockRecord candidate = GROUND_ANCHORS.get(random.nextInt(GROUND_ANCHORS.size()));
            if (primaryAnchors.contains(candidate) || selectedSmallAnchors.contains(candidate)) continue;
            for (BlockRecord primary : primaryAnchors) {
                double dx = candidate.x - primary.x;
                double dz = candidate.z - primary.z;
                double distanceSquared = dx * dx + dz * dz;
                if (distanceSquared >= 0.9D * 0.9D && distanceSquared <= 2.8D * 2.8D) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static BlockRecord pickAnchor(Random random, List<BlockRecord> used,
                                          double minSpacing, double minRadius, double maxRadius) {
        // Try full spacing first, then relax it gradually to guarantee the
        // requested number of fragments on uneven or sparse terrain.
        double[] spacingFactors = {1.0D, 0.72D, 0.42D, 0.0D};
        for (double spacingFactor : spacingFactors) {
            double minSpacingSquared = minSpacing * minSpacing
                    * spacingFactor * spacingFactor;

            for (int attempt = 0; attempt < Math.max(900, GROUND_ANCHORS.size() * 2); attempt++) {
                BlockRecord candidate = GROUND_ANCHORS.get(random.nextInt(GROUND_ANCHORS.size()));
                if (used.contains(candidate)
                        || !isSafeVisibleAnchor(candidate, minRadius, maxRadius)) continue;

                boolean spaced = true;
                for (BlockRecord chosen : used) {
                    double sx = candidate.x - chosen.x;
                    double sz = candidate.z - chosen.z;
                    if (sx * sx + sz * sz < minSpacingSquared) {
                        spaced = false;
                        break;
                    }
                }
                if (spaced) return candidate;
            }
        }
        return null;
    }

    private static boolean isSafeVisibleAnchor(BlockRecord candidate,
                                               double minRadius, double maxRadius) {
        double dx = candidate.x + 0.5D - centerX;
        double dz = candidate.z + 0.5D - centerZ;
        double distanceSquared = dx * dx + dz * dz;
        // Each tier is distributed through the full 360-degree ring.
        return distanceSquared >= minRadius * minRadius
                && distanceSquared <= maxRadius * maxRadius;
    }

    private static void addRock(BlockRecord anchor, Random random,
                                float scale, float radialTravel, int emergenceTicks,
                                int sizeClass) {
        double dx = anchor.x + 0.5D - centerX;
        double dz = anchor.z + 0.5D - centerZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        float directionX = length > 0.001D ? (float) (dx / length) : 0.0F;
        float directionZ = length > 0.001D ? (float) (dz / length) : 0.0F;

        float tangent = (random.nextFloat() - 0.5F) * 0.22F;
        float driftX = directionX - directionZ * tangent;
        float driftZ = directionZ + directionX * tangent;
        float driftLength = (float) Math.sqrt(driftX * driftX + driftZ * driftZ);
        if (driftLength > 0.001F) {
            driftX /= driftLength;
            driftZ /= driftLength;
        }

        long meshSeed = random.nextLong();
        RockMesh mesh = ROCK_MESH_CACHE.get(meshSeed);
        if (mesh == null) {
            mesh = createRockMesh(new Random(meshSeed), sizeClass);
            if (ROCK_MESH_CACHE.size() >= 128) ROCK_MESH_CACHE.clear();
            ROCK_MESH_CACHE.put(meshSeed, mesh);
        }

        float boundingRadius = 0.0F;
        for (float[] vertex : mesh.vertices) {
            float radius = (float) Math.sqrt(
                    vertex[0] * vertex[0] + vertex[1] * vertex[1] + vertex[2] * vertex[2]
            );
            boundingRadius = Math.max(boundingRadius, radius);
        }

        // The whole mesh begins below the top face. Captured terrain occludes it
        // until the rapid rise carries the fragment completely into open air.
        float buriedExtent = boundingRadius * scale;
        float startY = anchor.y + 1.0F - buriedExtent - 0.12F;
        float emergenceHeight = buriedExtent * 2.0F + 0.24F;

        float launchX = anchor.x + 0.5F + (random.nextFloat() - 0.5F) * 0.34F;
        float launchZ = anchor.z + 0.5F + (random.nextFloat() - 0.5F) * 0.34F;
        ROCKS.add(new Rock(
                launchX, startY, launchZ,
                driftX, driftZ, radialTravel, emergenceHeight,
                scale, random.nextInt(3), emergenceTicks,
                random.nextFloat() * 360.0F,
                (random.nextFloat() - 0.5F) * 18.0F,
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.055F + random.nextFloat() * 0.075F),
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.035F + random.nextFloat() * 0.055F),
                (random.nextFloat() - 0.5F) * 16.0F,
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.025F + random.nextFloat() * 0.045F),
                random.nextFloat() * (float) (Math.PI * 2.0D),
                0.045F + random.nextFloat() * 0.035F,
                mesh
        ));
        spawnRockParticles(anchor, random, scale);
    }

    /**
     * Emit a small, evenly distributed burst of block-colored dust across the
     * captured surface. This makes the fast terrain dissolve readable without
     * flooding the client with thousands of particles.
     */
    private static void spawnTerrainDissolveParticles(Random random) {
        if (capturedLevel == null || GROUND_ANCHORS.isEmpty()) return;

        int available = GROUND_ANCHORS.size();
        int count = Math.min(120, available);
        int start = random.nextInt(available);

        for (int i = 0; i < count; i++) {
            int index = (start + (int) ((long) i * available / count)) % available;
            BlockRecord anchor = GROUND_ANCHORS.get(index);
            double px = anchor.x + 0.12D + random.nextDouble() * 0.76D;
            double py = anchor.y + 0.10D + random.nextDouble() * 0.55D;
            double pz = anchor.z + 0.12D + random.nextDouble() * 0.76D;

            double dx = px - centerX;
            double dz = pz - centerZ;
            double distance = Math.sqrt(dx * dx + dz * dz);
            double outwardSpeed = 0.025D + random.nextDouble() * 0.085D;
            double vx = (distance > 1.0E-4D ? dx / distance * outwardSpeed : 0.0D)
                    + (random.nextDouble() - 0.5D) * 0.045D;
            double vz = (distance > 1.0E-4D ? dz / distance * outwardSpeed : 0.0D)
                    + (random.nextDouble() - 0.5D) * 0.045D;
            double vy = 0.035D + random.nextDouble() * 0.13D;

            capturedLevel.addParticle(
                    new BlockParticleOption(ParticleTypes.BLOCK, anchor.state),
                    px, py, pz, vx, vy, vz
            );
        }
    }

    private static void spawnRockParticles(BlockRecord anchor, Random random, float scale) {
        if (capturedLevel == null) return;

        // A restrained burst of actual block debris at the launch point:
        // 3 particles for giant rocks, 2 for medium, 1 for small fragments.
        int count = scale >= 3.0F ? 3 : (scale >= 1.5F ? 2 : 1);
        BlockParticleOption debris = new BlockParticleOption(ParticleTypes.BLOCK, anchor.state);

        for (int i = 0; i < count; i++) {
            double px = anchor.x + 0.15D + random.nextDouble() * 0.70D;
            double py = anchor.y + 0.08D + random.nextDouble() * 0.30D;
            double pz = anchor.z + 0.15D + random.nextDouble() * 0.70D;
            double vx = (random.nextDouble() - 0.5D) * 0.20D;
            double vy = 0.08D + random.nextDouble() * 0.20D;
            double vz = (random.nextDouble() - 0.5D) * 0.20D;
            capturedLevel.addParticle(debris, px, py, pz, vx, vy, vz);
        }
    }

    /**
     * Every fragment receives a bounded, irregular low-poly mesh. Its ring
     * radii are deliberately kept below one local block unit so scaling a
     * giant rock cannot create screen-wide spikes.
     */
    /**
     * Builds a squat, irregular low-poly boulder. The top and bottom caps sit
     * close to their rings instead of stretching into pointed tips. Horizontal
     * dimensions stay comparable on X/Z; the shape is intentionally wider
     * than it is tall so the fragments read as broken rock, not spikes.
     */
    /**
     * Creates a sharp, irregular wall fragment from a deformed cuboid.
     * The five profiles deliberately vary the dominant axis: broad slabs,
     * upright splinters, long shards and chunky broken masonry. Each corner
     * is independently displaced, so every triangle becomes a flat chipped
     * facet instead of a patch on a spherical surface.
     */
    private static RockMesh createRockMesh(Random random, int sizeClass) {
        float halfX;
        float halfY;
        float halfZ;
        int profile;
        if (sizeClass == 0) {
            int choice = random.nextInt(10);
            profile = choice < 3 ? 0 : choice < 5 ? 2 : choice < 8 ? 4 : 5;
        } else {
            profile = random.nextInt(sizeClass == 1 ? 6 : 5);
        }

        // Preserve the existing size silhouettes, but build a coherent fractured
        // prism rather than independently twisting eight box corners.
        if (profile == 0) {
            halfX = 0.78F + random.nextFloat() * 0.18F;
            halfY = 0.30F + random.nextFloat() * 0.14F;
            halfZ = 0.58F + random.nextFloat() * 0.18F;
        } else if (profile == 1) {
            halfX = 0.30F + random.nextFloat() * 0.16F;
            halfY = 0.78F + random.nextFloat() * 0.18F;
            halfZ = 0.48F + random.nextFloat() * 0.20F;
        } else if (profile == 2) {
            halfX = 0.86F + random.nextFloat() * 0.16F;
            halfY = 0.32F + random.nextFloat() * 0.18F;
            halfZ = 0.30F + random.nextFloat() * 0.16F;
        } else if (profile == 3) {
            halfX = 0.58F + random.nextFloat() * 0.20F;
            halfY = 0.54F + random.nextFloat() * 0.22F;
            halfZ = 0.58F + random.nextFloat() * 0.20F;
        } else if (profile == 4) {
            halfX = 0.70F + random.nextFloat() * 0.18F;
            halfY = 0.46F + random.nextFloat() * 0.18F;
            halfZ = 0.32F + random.nextFloat() * 0.16F;
        } else {
            halfX = 1.02F + random.nextFloat() * 0.16F;
            halfY = 0.24F + random.nextFloat() * 0.10F;
            halfZ = 0.30F + random.nextFloat() * 0.12F;
        }

        // Only a portion of large and medium fragments receive chipped,
        // slightly pointed corners; the overall silhouettes remain broad slabs.
        boolean sharpenedEdges = sizeClass == 0
                ? random.nextFloat() < 0.42F
                : sizeClass == 1 && random.nextFloat() < 0.32F;
        int sides = 5 + random.nextInt(3);
        float phase = random.nextFloat() * (float) (Math.PI * 2.0D);
        float ringTwist = (random.nextFloat() - 0.5F) * (sharpenedEdges ? 0.15F : 0.10F);
        float leanX = (random.nextFloat() - 0.5F) * halfX * 0.10F;
        float leanZ = (random.nextFloat() - 0.5F) * halfZ * 0.10F;
        float[] edgePeaks = new float[sides];
        for (int side = 0; side < sides; side++) {
            edgePeaks[side] = sharpenedEdges && random.nextFloat() < 0.40F
                    ? 0.10F + random.nextFloat() * 0.12F
                    : sharpenedEdges ? -random.nextFloat() * 0.025F : 0.0F;
        }
        float[][] vertices = new float[sides * 3 + 2][3];

        // Three rings give each fragment a broad central body, chipped upper
        // and lower edges, and connected planes with no pinched/twisted corners.
        for (int ring = 0; ring < 3; ring++) {
            for (int side = 0; side < sides; side++) {
                float angle = phase + (float) (Math.PI * 2.0D * side / sides)
                        + (ring - 1) * ringTwist
                        + (ring == 2 ? edgePeaks[side] * 0.10F : 0.0F);
                float radialNoise = 0.90F + random.nextFloat() * 0.18F;
                float edgeWave = sharpenedEdges ? 0.075F : 0.045F;
                float chippedEdge = 1.0F
                        + (float) Math.sin(side * 2.31F + phase) * edgeWave
                        + edgePeaks[side];
                float radius = radialNoise * chippedEdge;
                float ringX = ring == 0 ? -leanX * 0.25F
                        : ring == 1 ? leanX : leanX * 0.55F;
                float ringZ = ring == 0 ? -leanZ * 0.25F
                        : ring == 1 ? leanZ : leanZ * 0.55F;
                float x = (float) Math.cos(angle) * halfX * radius + ringX;
                float z = (float) Math.sin(angle) * halfZ * radius + ringZ;
                float y;
                if (ring == 0) {
                    y = -halfY * (0.76F + random.nextFloat() * 0.16F);
                } else if (ring == 1) {
                    y = (random.nextFloat() - 0.5F) * halfY * 0.24F;
                } else {
                    y = halfY * (0.72F + random.nextFloat() * 0.20F
                            + edgePeaks[side] * 0.60F);
                }
                vertices[ring * sides + side] = new float[] {x, y, z};
            }
        }

        int lowerCenter = sides * 3;
        int upperCenter = lowerCenter + 1;
        vertices[lowerCenter] = new float[] {
                leanX * 0.25F, -halfY * 0.78F, leanZ * 0.25F
        };
        vertices[upperCenter] = new float[] {
                leanX * 0.55F, halfY * 0.76F, leanZ * 0.55F
        };

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

        return new RockMesh(vertices, faces);
    }

    private static void renderRocks(PoseStack poseStack,
                                    MultiBufferSource.BufferSource buffer,
                                    View camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        TextureAtlasSprite stoneSprite = minecraft.getBlockRenderer()
                .getBlockModelShaper()
                .getBlockModel(Blocks.STONE.defaultBlockState())
                .getParticleIcon();
        VertexConsumer consumer = buffer.getBuffer(ROCK_RENDER_TYPE);

        for (Rock rock : ROCKS) {
            float age = elapsedTicks + partialTick - SHATTER_TICK - rock.spawnDelay;
            if (age < 0.0F || age >= ROCK_LIFETIME_TICKS) continue;

            float fadeStart = ROCK_LIFETIME_TICKS - ROCK_FADE_TICKS;
            float alpha = age <= fadeStart ? 1.0F
                    : (ROCK_LIFETIME_TICKS - age) / (float) ROCK_FADE_TICKS;
            int alphaByte = (int) (Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);

            float emergenceProgress = Math.max(0.0F, Math.min(1.0F,
                    age / (float) Math.max(1, rock.emergenceTicks)));
            float emergenceEase = 1.0F - (float) Math.pow(1.0F - emergenceProgress, 3.0D);
            float driftProgress = Math.max(0.0F, Math.min(1.0F,
                    (age - rock.emergenceTicks)
                            / (float) Math.max(1, ROCK_LIFETIME_TICKS - rock.emergenceTicks)));
            driftProgress = driftProgress * driftProgress * (3.0F - 2.0F * driftProgress);

            double x = rock.x + rock.directionX * rock.radialTravel * driftProgress;
            double y = rock.y + rock.emergenceHeight * emergenceEase
                    + Math.sin(age * 0.11F + rock.hoverPhase) * rock.hoverAmplitude
                    + driftProgress * 0.12F;
            double z = rock.z + rock.directionZ * rock.radialTravel * driftProgress;
            if (!isInView(x, y, z, camera)) continue;

            poseStack.pushPose();
            try {
                poseStack.translate(x - camera.x, y - camera.y, z - camera.z);
                poseStack.mulPose(Axis.YP.rotationDegrees(rock.yaw + age * rock.yawSpeed));
                poseStack.mulPose(Axis.XP.rotationDegrees(rock.pitch + age * rock.pitchSpeed));
                poseStack.mulPose(Axis.ZP.rotationDegrees(rock.roll + age * rock.rollSpeed));
                poseStack.scale(rock.scale, rock.scale, rock.scale);

                Matrix4f matrix = poseStack.last().pose();
                org.joml.Matrix3f normalMatrix = poseStack.last().normal();

                float centerX = 0.0F, centerY = 0.0F, centerZ = 0.0F;
                for (float[] vertex : rock.mesh.vertices) {
                    centerX += vertex[0];
                    centerY += vertex[1];
                    centerZ += vertex[2];
                }
                centerX /= rock.mesh.vertices.length;
                centerY /= rock.mesh.vertices.length;
                centerZ /= rock.mesh.vertices.length;

                for (int[] face : rock.mesh.faces) {
                    float[] a = rock.mesh.vertices[face[0]];
                    float[] b = rock.mesh.vertices[face[1]];
                    float[] c = rock.mesh.vertices[face[2]];

                    float ux = b[0] - a[0];
                    float uy = b[1] - a[1];
                    float uz = b[2] - a[2];
                    float vx = c[0] - a[0];
                    float vy = c[1] - a[1];
                    float vz = c[2] - a[2];

                    float nx = uy * vz - uz * vy;
                    float ny = uz * vx - ux * vz;
                    float nz = ux * vy - uy * vx;
                    float normalLength = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    if (normalLength < 1.0E-5F) continue;
                    nx /= normalLength;
                    ny /= normalLength;
                    nz /= normalLength;

                    float faceCenterX = (a[0] + b[0] + c[0]) / 3.0F - centerX;
                    float faceCenterY = (a[1] + b[1] + c[1]) / 3.0F - centerY;
                    float faceCenterZ = (a[2] + b[2] + c[2]) / 3.0F - centerZ;
                    if (nx * faceCenterX + ny * faceCenterY + nz * faceCenterZ < 0.0F) {
                        float[] swap = b;
                        b = c;
                        c = swap;
                        nx = -nx;
                        ny = -ny;
                        nz = -nz;
                    }

                    float diffuse = Math.max(0.0F, nx * -0.38F + ny * 0.84F + nz * 0.38F);
                    float shade = 0.24F + diffuse * 0.76F;
                    float variation = 0.82F + ((face[0] * 17 + face[1] * 11 + face[2] * 7) & 7) * 0.022F;
                    int color = (int) (235.0F * Math.min(1.0F, shade * variation));

                    // IMPORTANT: entityTranslucent uses QUADS. Sending only three
                    // vertices per face corrupts the quad stream: the next face's
                    // first vertex is consumed as this quad's fourth vertex. That
                    // creates the giant screen-spanning stretched polygons.
                    // Encode each triangle as a degenerate quad (A,B,C,C).
                    emitRockTriangle(consumer, matrix, normalMatrix, stoneSprite,
                            a, b, c, color, alphaByte, nx, ny, nz);
                }
            } finally {
                poseStack.popPose();
            }
        }

        // Flush only this render type; do not leave an unfinished vertex batch.
        buffer.endBatch(ROCK_RENDER_TYPE);
    }

    private static void emitRockTriangle(VertexConsumer consumer,
                                         Matrix4f matrix,
                                         org.joml.Matrix3f normalMatrix,
                                         TextureAtlasSprite sprite,
                                         float[] a, float[] b, float[] c,
                                         int color, int alpha,
                                         float nx, float ny, float nz) {
        emitRockVertex(consumer, matrix, normalMatrix, sprite, a, TRIANGLE_UV[0],
                color, alpha, nx, ny, nz);
        emitRockVertex(consumer, matrix, normalMatrix, sprite, b, TRIANGLE_UV[1],
                color, alpha, nx, ny, nz);
        emitRockVertex(consumer, matrix, normalMatrix, sprite, c, TRIANGLE_UV[2],
                color, alpha, nx, ny, nz);
        // A duplicate last vertex completes the QUADS primitive without adding
        // a second visible triangle (the fourth vertex makes a degenerate one).
        emitRockVertex(consumer, matrix, normalMatrix, sprite, c, TRIANGLE_UV[2],
                color, alpha, nx, ny, nz);
    }

    private static void emitRockVertex(VertexConsumer consumer,
                                       Matrix4f matrix,
                                       org.joml.Matrix3f normalMatrix,
                                       TextureAtlasSprite sprite,
                                       float[] point, float[] uv,
                                       int color, int alpha,
                                       float nx, float ny, float nz) {
        consumer.vertex(matrix, point[0], point[1], point[2])
                .color(color, color, color, alpha)
                .uv(sprite.getU(uv[0]), sprite.getV(uv[1]))
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, nx, ny, nz)
                .endVertex();
    }

    /** Returns normalized upward-travel progress, or -1 before the scheduled pass. */
    public static float getSlashRevealProgress(float partialTick) {
        if (!effectActive || effectStartNanos == 0L) return -1.0F;
        double elapsedSeconds = (System.nanoTime() - effectStartNanos) / 1_000_000_000.0D;
        double slashAge = elapsedSeconds - SLASH_DELAY_SECONDS;
        if (slashAge < 0.0D || slashAge > SLASH_TRAVEL_SECONDS + SLASH_FADE_SECONDS) {
            return -1.0F;
        }
        return (float) Math.max(0.0D, Math.min(1.0D, slashAge / SLASH_TRAVEL_SECONDS));
    }

    /** Hold full opacity during the pass, then softly fade after leaving the top edge. */
    public static float getSlashFade(float partialTick) {
        if (!effectActive || effectStartNanos == 0L) return 0.0F;
        double elapsedSeconds = (System.nanoTime() - effectStartNanos) / 1_000_000_000.0D;
        double slashAge = elapsedSeconds - SLASH_DELAY_SECONDS;
        if (slashAge < 0.0D || slashAge > SLASH_TRAVEL_SECONDS + SLASH_FADE_SECONDS) {
            return 0.0F;
        }
        if (slashAge <= SLASH_TRAVEL_SECONDS) return 1.0F;
        return (float) Math.max(0.0D,
                1.0D - (slashAge - SLASH_TRAVEL_SECONDS) / SLASH_FADE_SECONDS);
    }

    /**
     * Predicts a 1.25-second visual future for a living entity. This is strictly
     * client-side rendering: the real entity, its hitbox and server movement
     * remain authoritative and are never teleported or overwritten.
     */
    public static Vec3 getPredictedFuturePosition(LivingEntity target, float partialTick) {
        if (target == null) return null;

        Vec3 rawPrediction = predictForward(target, partialTick, FUTURE_LEAD_TICKS);
        int entityId = target.getId();
        if (rawPrediction == null) {
            FUTURE_PREDICTIONS.remove(entityId);
            FUTURE_PARTICLE_TIMES.remove(entityId);
            return null;
        }

        long now = System.nanoTime();
        PredictionSmoother smoother = FUTURE_PREDICTIONS.get(entityId);
        if (smoother == null) {
            smoother = new PredictionSmoother(rawPrediction, now);
            FUTURE_PREDICTIONS.put(entityId, smoother);
        } else {
            long elapsedNanos = Math.max(0L, now - smoother.lastUpdateNanos);
            double elapsedSeconds = Math.min(0.20D, elapsedNanos / 1_000_000_000.0D);
            // 105 ms time constant removes frame-to-frame snapping without
            // making the hologram feel sluggish when an entity changes direction.
            double blend = 1.0D - Math.exp(-elapsedSeconds / 0.105D);
            if (elapsedNanos > 450_000_000L) {
                smoother.position = rawPrediction;
            } else {
                smoother.position = smoother.position.lerp(rawPrediction, blend);
            }
            smoother.lastUpdateNanos = now;
        }

        spawnFutureResidue(target, smoother.position, target.getPosition(partialTick), now);
        return smoother.position;
    }

    /**
     * Small red/purple motes and a soft smoke wisp leave a short residue around
     * the future image. Emission is throttled per entity to keep particle cost low.
     */
    private static void spawnFutureResidue(LivingEntity target, Vec3 future,
                                           Vec3 current, long now) {
        int entityId = target.getId();
        Long last = FUTURE_PARTICLE_TIMES.get(entityId);
        if (last != null && now - last < FUTURE_PARTICLE_INTERVAL_NANOS) return;
        FUTURE_PARTICLE_TIMES.put(entityId, now);

        if (!(target.level() instanceof ClientLevel clientLevel)) return;
        net.minecraft.util.RandomSource random = target.getRandom();
        double height = target.getBbHeight();
        Vec3 towardCurrent = current.subtract(future);
        double distance = towardCurrent.length();
        Vec3 trailDirection = distance > 1.0E-4D
                ? towardCurrent.scale(1.0D / distance)
                : Vec3.ZERO;

        double hazeX = future.x + (random.nextDouble() - 0.5D) * 0.28D;
        double hazeY = future.y + height * (0.48D + random.nextDouble() * 0.30D);
        double hazeZ = future.z + (random.nextDouble() - 0.5D) * 0.28D;
        clientLevel.addParticle(ParticleTypes.SMOKE,
                hazeX, hazeY, hazeZ,
                (random.nextDouble() - 0.5D) * 0.004D, 0.003D,
                (random.nextDouble() - 0.5D) * 0.004D);

        double trailDistance = Math.min(0.85D, Math.max(0.30D, distance * 0.28D));
        double trailX = future.x + trailDirection.x * trailDistance;
        double trailY = future.y + height * 0.42D;
        double trailZ = future.z + trailDirection.z * trailDistance;
        clientLevel.addParticle(
                new net.minecraft.core.particles.DustParticleOptions(
                        new org.joml.Vector3f(0.72F, 0.005F, 0.025F), 0.72F),
                trailX + (random.nextDouble() - 0.5D) * 0.22D,
                trailY + (random.nextDouble() - 0.5D) * 0.30D,
                trailZ + (random.nextDouble() - 0.5D) * 0.22D,
                trailDirection.x * 0.008D, 0.006D, trailDirection.z * 0.008D);
    }

    /** Moves only the rendered real model a short step along the same path. */
    public static Vec3 getVisualBodyOffset(LivingEntity target, float partialTick) {
        Vec3 future = getPredictedFuturePosition(target, partialTick);
        if (future == null) return null;
        Vec3 origin = target.getPosition(partialTick);
        // The real model advances only one fifth of the prediction interval,
        // leaving the full 1.25-second red hologram clearly ahead of it.
        return future.subtract(origin).scale(0.20D);
    }

    private static Vec3 predictForward(LivingEntity target, float partialTick, int leadTicks) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer observer = minecraft.player;
        if (!ClientUtil.isUsingTimeErase || observer == null || target == null
                || target == observer || !target.isAlive()
                || target.level() != observer.level()
                || target instanceof StandEntity
                || (target instanceof KingCrimsonCloneEntity clone && clone.getPlayer() == observer)
                || target instanceof AbstractFish || target instanceof WaterAnimal) {
            return null;
        }

        Vec3 origin = target.getPosition(partialTick);
        Vec3 toTarget = origin.subtract(observer.getEyePosition(partialTick));
        double distanceSquared = toTarget.lengthSqr();
        if (distanceSquared < 0.01D || distanceSquared > 625.0D) return null;

        Vec3 view = observer.getViewVector(partialTick).normalize();
        Vec3 directionToTarget = toTarget.scale(1.0D / Math.sqrt(distanceSquared));
        // A forward cone approximating the player's visible field of view.
        if (view.dot(directionToTarget) < 0.45D) return null;

        Vec3 movement = target.getDeltaMovement();
        Vec3 horizontalMovement = new Vec3(movement.x, 0.0D, movement.z);
        double speed = horizontalMovement.length();
        Vec3 direction;
        if (speed > 0.015D) {
            direction = horizontalMovement.scale(1.0D / speed);
        } else {
            Vec3 gaze = target.getViewVector(partialTick);
            Vec3 horizontalGaze = new Vec3(gaze.x, 0.0D, gaze.z);
            double gazeLength = horizontalGaze.length();
            if (gazeLength < 1.0E-4D) return null;
            direction = horizontalGaze.scale(1.0D / gazeLength);
        }

        double leadDistance = speed > 0.015D
                ? Math.max(0.20D, Math.min(5.5D, speed * leadTicks))
                : 1.10D;
        Vec3 predicted = origin.add(direction.scale(leadDistance));

        if (target.onGround()) {
            int groundY = target.level().getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    net.minecraft.util.Mth.floor(predicted.x),
                    net.minecraft.util.Mth.floor(predicted.z)
            );
            if (Math.abs(groundY - origin.y) <= 3.0D) {
                predicted = new Vec3(predicted.x, groundY, predicted.z);
            }
        } else {
            double verticalLead = Math.max(-2.0D, Math.min(2.5D, movement.y * leadTicks));
            predicted = new Vec3(predicted.x, origin.y + verticalLead, predicted.z);
        }

        // Do not place the hologram inside a wall. Back the lead off until the
        // predicted entity box has a clear position, preserving the same path.
        for (int attempt = 0; attempt < 10; attempt++) {
            Vec3 offset = predicted.subtract(origin);
            if (target.level().noCollision(target, target.getBoundingBox().move(offset))) {
                return predicted;
            }
            leadDistance *= 0.78D;
            predicted = origin.add(direction.scale(leadDistance));
            if (target.onGround()) {
                int groundY = target.level().getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        net.minecraft.util.Mth.floor(predicted.x),
                        net.minecraft.util.Mth.floor(predicted.z)
                );
                predicted = new Vec3(predicted.x, groundY, predicted.z);
            }
        }
        return origin;
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
        return dot > -0.65D;
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

    private static final class PredictionSmoother {
        Vec3 position;
        long lastUpdateNanos;

        PredictionSmoother(Vec3 position, long lastUpdateNanos) {
            this.position = position;
            this.lastUpdateNanos = lastUpdateNanos;
        }
    }

    private static final class BlockSnapshot {
        final int x, y, z;
        final BlockState state;

        BlockSnapshot(int x, int y, int z, BlockState state) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
        }
    }

    private static final class BlockRecord {
        final int x, y, z;
        final BlockState state;
        final int packedLight;

        BlockRecord(ClientLevel level, BlockSnapshot snapshot) {
            x = snapshot.x;
            y = snapshot.y;
            z = snapshot.z;
            state = snapshot.state;
            packedLight = LevelRenderer.getLightColor(level, new BlockPos(x, y, z));
        }
    }

    private static final class WorldCrackSegment {
        final float startX;
        final float startZ;
        final float endX;
        final float endZ;
        final float halfWidth;
        final float lipHeight;
        final long seed;

        WorldCrackSegment(float startX, float startZ, float endX, float endZ,
                          float halfWidth, float lipHeight, long seed) {
            this.startX = startX;
            this.startZ = startZ;
            this.endX = endX;
            this.endZ = endZ;
            this.halfWidth = halfWidth;
            this.lipHeight = lipHeight;
            this.seed = seed;
        }
    }

    private static final class CrackPoint {
        final float x;
        final float z;

        CrackPoint(float x, float z) {
            this.x = x;
            this.z = z;
        }
    }

    private static final class CrackPath {
        final List<CrackPoint> points;
        final float halfWidth;
        final float lipHeight;

        CrackPath(List<CrackPoint> points, float halfWidth, float lipHeight) {
            this.points = points;
            this.halfWidth = halfWidth;
            this.lipHeight = lipHeight;
        }
    }

    private static final class GroundCrack {
        final BlockRecord record;
        final TextureAtlasSprite sprite;
        final float[] positions;
        final float[] uvs;
        final int[] colors;
        final float[] reveal;
        final int quadCount;

        GroundCrack(BlockRecord record, TextureAtlasSprite sprite,
                    float[] positions, float[] uvs, int[] colors,
                    float[] reveal, int quadCount) {
            this.record = record;
            this.sprite = sprite;
            this.positions = positions;
            this.uvs = uvs;
            this.colors = colors;
            this.reveal = reveal;
            this.quadCount = quadCount;
        }
    }

    /** Primitive-array mesh builder keeps the thousands of cracks lightweight. */
    private static final class CrackMeshBuilder {
        float[] positions = new float[384];
        float[] uvs = new float[256];
        int[] colors = new int[32];
        float[] reveal = new float[32];
        int quadCount;

        void addQuad(float[] a, float[] b, float[] c, float[] d,
                     int color, float revealAt) {
            ensureCapacity();
            int p = quadCount * 12;
            int t = quadCount * 8;
            putPoint(positions, p, a);
            putPoint(positions, p + 3, b);
            putPoint(positions, p + 6, c);
            putPoint(positions, p + 9, d);
            putUv(uvs, t, a);
            putUv(uvs, t + 2, b);
            putUv(uvs, t + 4, c);
            putUv(uvs, t + 6, d);
            colors[quadCount] = color;
            reveal[quadCount] = revealAt;
            quadCount++;
        }

        private static void putPoint(float[] target, int offset, float[] point) {
            target[offset] = point[0];
            target[offset + 1] = point[1];
            target[offset + 2] = point[2];
        }

        private static void putUv(float[] target, int offset, float[] point) {
            target[offset] = Math.max(0.0F, Math.min(1.0F, point[0])) * 16.0F;
            target[offset + 1] = Math.max(0.0F, Math.min(1.0F, point[2])) * 16.0F;
        }

        private void ensureCapacity() {
            if (quadCount * 12 + 12 > positions.length) {
                positions = Arrays.copyOf(positions, positions.length * 2);
                uvs = Arrays.copyOf(uvs, uvs.length * 2);
                colors = Arrays.copyOf(colors, colors.length * 2);
                reveal = Arrays.copyOf(reveal, reveal.length * 2);
            }
        }

        GroundCrack build(BlockRecord record, TextureAtlasSprite sprite) {
            return new GroundCrack(record, sprite,
                    Arrays.copyOf(positions, quadCount * 12),
                    Arrays.copyOf(uvs, quadCount * 8),
                    Arrays.copyOf(colors, quadCount),
                    Arrays.copyOf(reveal, quadCount),
                    quadCount);
        }
    }

    private static final class RockMesh {
        final float[][] vertices;
        final int[][] faces;

        RockMesh(float[][] vertices, int[][] faces) {
            this.vertices = vertices;
            this.faces = faces;
        }
    }

    private static final class Rock {
        final float x, y, z;
        final float directionX, directionZ;
        final float radialTravel, emergenceHeight;
        final float scale;
        final int spawnDelay, emergenceTicks;
        final float yaw, pitch, yawSpeed, pitchSpeed, roll, rollSpeed;
        final float hoverPhase, hoverAmplitude;
        final RockMesh mesh;

        Rock(float x, float y, float z,
             float directionX, float directionZ,
             float radialTravel, float emergenceHeight,
             float scale, int spawnDelay, int emergenceTicks,
             float yaw, float pitch, float yawSpeed, float pitchSpeed,
             float roll, float rollSpeed,
             float hoverPhase, float hoverAmplitude, RockMesh mesh) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.directionX = directionX;
            this.directionZ = directionZ;
            this.radialTravel = radialTravel;
            this.emergenceHeight = emergenceHeight;
            this.scale = scale;
            this.spawnDelay = spawnDelay;
            this.emergenceTicks = emergenceTicks;
            this.yaw = yaw;
            this.pitch = pitch;
            this.yawSpeed = yawSpeed;
            this.pitchSpeed = pitchSpeed;
            this.roll = roll;
            this.rollSpeed = rollSpeed;
            this.hoverPhase = hoverPhase;
            this.hoverAmplitude = hoverAmplitude;
            this.mesh = mesh;
        }
    }
}
