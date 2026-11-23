package net.hydra.jojomod.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.hydra.jojomod.sound.ModSounds;
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

    private static final int SHATTER_TICK = 46;
    private static final int CAMERA_SHAKE_END_TICK = 64;
    private static final int CUSTOM_THEME_TICK = 84;
    private static final int ROCK_LIFETIME_TICKS = 50;
    private static final int ROCK_FADE_TICKS = 10;
    private static final int ROCK_EMERGENCE_TICKS = 7;
    // Keep the captured scene visible while the first fragments emerge, so the cut
    // never leaves a half-second empty gap before the debris becomes visible.
    private static final int POST_SHATTER_SCENE_HOLD_TICKS = 10;
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

        // Do not allow the Diavolo track to outlive the Time Erase ability.
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
        capturedLevel = level;
        centerX = player.getX();
        centerY = player.getY();
        centerZ = player.getZ();

        elapsedTicks = 0;
        effectStartNanos = System.nanoTime();
        effectActive = true;
        customThemePlayed = false;
        rocksCreated = false;
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
        capturedLevel = null;
        previousAbilityActive = false;
        effectActive = false;
        customThemePlayed = false;
        rocksCreated = false;
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
        if (!effectActive || !ClientUtil.renderTimeErase()) return;

        View view = new View(camera);
        // Keep the captured scene as a short afterimage while the newly spawned
        // rocks rise through the ground. At ten ticks (0.5 seconds), the fragments
        // are already fully emerged and the old scene can disappear without a void.
        if (elapsedTicks < SHATTER_TICK + POST_SHATTER_SCENE_HOLD_TICKS) {
            renderCapturedScene(poseStack, buffer, view);
            buffer.endBatch();
        }
        if (elapsedTicks >= SHATTER_TICK && !ROCKS.isEmpty()) {
            renderRocks(poseStack, buffer, view, partialTick);
        }
    }

    private static void renderCapturedScene(PoseStack poseStack, MultiBufferSource buffer,
                                            View camera) {
        Minecraft minecraft = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = minecraft.getBlockRenderer();

        for (BlockRecord record : BLOCKS) {
            if (!isInView(record.x + 0.5D, record.y + 0.5D, record.z + 0.5D, camera)) continue;

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
