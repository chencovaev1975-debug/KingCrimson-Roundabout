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
    private static final int PURPLE_FLASH_START_TICK = 21;
    private static final int PURPLE_FLASH_DURATION_TICKS = 4;
    private static final int BIG_ROCK_COUNT = 7;
    private static final int MEDIUM_ROCK_COUNT = 4;
    private static final int SMALL_ROCK_COUNT = 6;
    private static final int CRUMB_ROCK_COUNT = 16;

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
            new ArrayList<>(BIG_ROCK_COUNT + MEDIUM_ROCK_COUNT + SMALL_ROCK_COUNT + CRUMB_ROCK_COUNT);
    // Generated meshes are immutable after creation and reused by seed.
    private static final Map<Long, RockMesh> ROCK_MESH_CACHE = new HashMap<>(64);

    private static boolean previousAbilityActive;
    private static boolean effectActive;
    private static boolean customThemePlayed;
    private static boolean rocksCreated;
    private static int elapsedTicks;
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

        if (!abilityActive && previousAbilityActive) {
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
            } else {
                elapsedTicks++;

                // Capture one one-block-wide ring per tick. Thirty small scans
                // finish before the shatter and avoid a heavy 30-block-radius frame.
                if (elapsedTicks < BAND_COUNT && capturedLevel != null) {
                    captureBand(capturedLevel,
                            (int) Math.floor(centerX),
                            (int) Math.floor(centerY),
                            (int) Math.floor(centerZ),
                            elapsedTicks);
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
        effectActive = true;
        customThemePlayed = false;
        rocksCreated = false;
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
        appliedCameraShake = 0.0F;
    }

    private static void stopTheme() {
        if (themeInstance != null) {
            Minecraft.getInstance().getSoundManager().stop(themeInstance);
            themeInstance = null;
        }
    }

    /**
     * Capture exposed block models in one five-block-wide ring. The scan extends
     * above the ground to include trees and structures, and a few blocks below
     * the surface to preserve exposed cliff faces. Buried underground mass is
     * intentionally excluded.
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
        if (elapsedTicks < SHATTER_TICK) {
            renderCapturedScene(poseStack, buffer, view);
            buffer.endBatch();
        } else if (!ROCKS.isEmpty()) {
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
     * A single soft violet screen flash, beginning exactly 1.05 seconds
     * after Time Erase starts. The HUD mixin draws it as one fullscreen tint.
     */
    public static float purpleFlashAlpha(float partialTick) {
        if (!effectActive) return 0.0F;

        float age = elapsedTicks + partialTick - PURPLE_FLASH_START_TICK;
        if (age < 0.0F || age >= PURPLE_FLASH_DURATION_TICKS) return 0.0F;

        float progress = age / (float) PURPLE_FLASH_DURATION_TICKS;
        return 0.36F * (float) Math.sin(Math.PI * progress);
    }

    /**
     * Seven giant boulders, four medium chunks, six small chunks and sixteen
     * tiny crumbs. Wide spacing is reserved for the giants so their silhouettes
     * do not overlap into one screen-filling mass.
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
                BIG_ROCK_COUNT + MEDIUM_ROCK_COUNT + SMALL_ROCK_COUNT + CRUMB_ROCK_COUNT
        );

        for (int i = 0; i < BIG_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchor(random, usedAnchors, 8.0D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            addRock(anchor, random,
                    3.15F + random.nextFloat() * 0.35F,
                    0.010F + random.nextFloat() * 0.012F,
                    0.075F + random.nextFloat() * 0.025F);
        }

        for (int i = 0; i < MEDIUM_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchor(random, usedAnchors, 4.2D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            addRock(anchor, random,
                    1.55F + random.nextFloat() * 0.35F,
                    0.016F + random.nextFloat() * 0.016F,
                    0.085F + random.nextFloat() * 0.025F);
        }

        for (int i = 0; i < SMALL_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchor(random, usedAnchors, 2.2D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            addRock(anchor, random,
                    0.78F + random.nextFloat() * 0.20F,
                    0.024F + random.nextFloat() * 0.022F,
                    0.095F + random.nextFloat() * 0.025F);
        }

        for (int i = 0; i < CRUMB_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchor(random, usedAnchors, 0.75D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            addRock(anchor, random,
                    0.18F + random.nextFloat() * 0.13F,
                    0.035F + random.nextFloat() * 0.030F,
                    0.12F + random.nextFloat() * 0.045F);
        }
    }

    private static BlockRecord pickAnchor(Random random, List<BlockRecord> used, double minSpacing) {
        // Try full spacing first, then relax it gradually to guarantee the
        // requested number of fragments on uneven or sparse terrain.
        double[] spacingFactors = {1.0D, 0.72D, 0.42D, 0.0D};
        for (double spacingFactor : spacingFactors) {
            double minSpacingSquared = minSpacing * minSpacing
                    * spacingFactor * spacingFactor;

            for (int attempt = 0; attempt < Math.max(900, GROUND_ANCHORS.size() * 2); attempt++) {
                BlockRecord candidate = GROUND_ANCHORS.get(random.nextInt(GROUND_ANCHORS.size()));
                if (used.contains(candidate) || !isSafeVisibleAnchor(candidate)) continue;

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

    private static boolean isSafeVisibleAnchor(BlockRecord candidate) {
        double dx = candidate.x + 0.5D - centerX;
        double dz = candidate.z + 0.5D - centerZ;
        double distanceSquared = dx * dx + dz * dz;

        // No dependency on camera yaw or look direction: distribute fragments
        // throughout the full 360-degree area around the player's position.
        // Rocks behind the player remain in the world and appear when the
        // player turns toward them.
        return distanceSquared >= 30.25D && distanceSquared <= 841.0D;
    }

    private static void addRock(BlockRecord anchor, Random random,
                                float scale, float horizontalSpeed, float verticalSpeed) {
        double dx = anchor.x + 0.5D - centerX;
        double dz = anchor.z + 0.5D - centerZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        float directionX = length > 0.001D ? (float) (dx / length) : 0.0F;
        float directionZ = length > 0.001D ? (float) (dz / length) : 0.0F;

        float velocityX = directionX * horizontalSpeed
                + (random.nextFloat() - 0.5F) * 0.012F;
        float velocityZ = directionZ * horizontalSpeed
                + (random.nextFloat() - 0.5F) * 0.012F;
        float velocityY = verticalSpeed;

        // A per-piece seed makes the generated polyhedron stable and cacheable.
        long meshSeed = random.nextLong();
        RockMesh mesh = ROCK_MESH_CACHE.get(meshSeed);
        if (mesh == null) {
            mesh = createRockMesh(new Random(meshSeed));
            if (ROCK_MESH_CACHE.size() >= 128) {
                ROCK_MESH_CACHE.clear();
            }
            ROCK_MESH_CACHE.put(meshSeed, mesh);
        }

        float lowestVertexY = 0.0F;
        for (float[] vertex : mesh.vertices) {
            lowestVertexY = Math.min(lowestVertexY, vertex[1]);
        }
        float startY = anchor.y - lowestVertexY * scale + 0.025F;

        ROCKS.add(new Rock(
                anchor.x + 0.5F,
                startY,
                anchor.z + 0.5F,
                velocityX,
                velocityY,
                velocityZ,
                scale,
                random.nextInt(7),
                random.nextFloat() * 360.0F,
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.10F + random.nextFloat() * 0.18F),
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.07F + random.nextFloat() * 0.13F),
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.06F + random.nextFloat() * 0.12F),
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.06F + random.nextFloat() * 0.12F),
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
    private static RockMesh createRockMesh(Random random) {
        float halfX;
        float halfY;
        float halfZ;
        int profile = random.nextInt(5);

        if (profile == 0) {
            // Thin stone plate.
            halfX = 0.78F + random.nextFloat() * 0.18F;
            halfY = 0.30F + random.nextFloat() * 0.14F;
            halfZ = 0.58F + random.nextFloat() * 0.18F;
        } else if (profile == 1) {
            // Tall angular splinter from a wall edge.
            halfX = 0.30F + random.nextFloat() * 0.16F;
            halfY = 0.78F + random.nextFloat() * 0.18F;
            halfZ = 0.48F + random.nextFloat() * 0.20F;
        } else if (profile == 2) {
            // Long, narrow shard.
            halfX = 0.86F + random.nextFloat() * 0.16F;
            halfY = 0.32F + random.nextFloat() * 0.18F;
            halfZ = 0.30F + random.nextFloat() * 0.16F;
        } else if (profile == 3) {
            // Heavy block with no dominant axis.
            halfX = 0.58F + random.nextFloat() * 0.20F;
            halfY = 0.54F + random.nextFloat() * 0.22F;
            halfZ = 0.58F + random.nextFloat() * 0.20F;
        } else {
            // Thick wall slab, chipped along its narrow side.
            halfX = 0.70F + random.nextFloat() * 0.18F;
            halfY = 0.46F + random.nextFloat() * 0.18F;
            halfZ = 0.32F + random.nextFloat() * 0.16F;
        }

        float[][] vertices = new float[8][3];
        float leanX = (random.nextFloat() - 0.5F) * 0.16F * halfX;
        float leanY = (random.nextFloat() - 0.5F) * 0.10F * halfY;
        float leanZ = (random.nextFloat() - 0.5F) * 0.16F * halfZ;

        // One independently warped point in each octant. Keeping each point
        // in its original octant preserves a closed, box-derived polyhedron.
        for (int i = 0; i < 8; i++) {
            float sx = (i & 1) == 0 ? -1.0F : 1.0F;
            float sy = (i & 2) == 0 ? -1.0F : 1.0F;
            float sz = (i & 4) == 0 ? -1.0F : 1.0F;

            float rx = 0.84F + random.nextFloat() * 0.32F;
            float ry = 0.82F + random.nextFloat() * 0.36F;
            float rz = 0.84F + random.nextFloat() * 0.32F;

            vertices[i] = new float[] {
                    sx * halfX * rx + sy * leanX + (random.nextFloat() - 0.5F) * halfX * 0.08F,
                    sy * halfY * ry + sz * leanY + (random.nextFloat() - 0.5F) * halfY * 0.08F,
                    sz * halfZ * rz + sx * leanZ + (random.nextFloat() - 0.5F) * halfZ * 0.08F
            };
        }

        // Outward-wound sides of the deformed hexahedron.
        int[][] quads = {
                {0, 3, 2, 1}, // -Z
                {4, 5, 6, 7}, // +Z
                {0, 4, 7, 3}, // -X
                {1, 2, 6, 5}, // +X
                {0, 1, 5, 4}, // -Y
                {3, 7, 6, 2}  // +Y
        };

        // Each side is split along a randomized diagonal. The renderer computes
        // a separate normal for every triangle, giving crisp flat shading.
        int[][] faces = new int[12][3];
        int face = 0;
        for (int[] quad : quads) {
            int a = quad[0], b = quad[1], c = quad[2], d = quad[3];
            if (random.nextBoolean()) {
                faces[face++] = new int[] {a, b, c};
                faces[face++] = new int[] {a, c, d};
            } else {
                faces[face++] = new int[] {a, b, d};
                faces[face++] = new int[] {b, c, d};
            }
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

            double x = rock.x + rock.velocityX * age;
            double y = rock.y + rock.velocityY * age;
            double z = rock.z + rock.velocityZ * age;
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
        final float velocityX, velocityY, velocityZ;
        final float scale;
        final int spawnDelay;
        final float yaw, pitch, yawSpeed, pitchSpeed, roll, rollSpeed;
        final RockMesh mesh;

        Rock(float x, float y, float z,
             float velocityX, float velocityY, float velocityZ,
             float scale, int spawnDelay,
             float yaw, float yawSpeed, float pitchSpeed,
             float roll, float rollSpeed, RockMesh mesh) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.velocityX = velocityX;
            this.velocityY = velocityY;
            this.velocityZ = velocityZ;
            this.scale = scale;
            this.spawnDelay = spawnDelay;
            this.yaw = yaw;
            this.pitch = (yaw * 0.17F) % 35.0F;
            this.yawSpeed = yawSpeed;
            this.pitchSpeed = pitchSpeed;
            this.roll = roll;
            this.rollSpeed = rollSpeed;
            this.mesh = mesh;
        }
    }
}
