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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Client-only Time Erase scene snapshot.
 *
 * The visible block shell (terrain, trees, leaves and other block models) is
 * captured in a bounded 20-block radius. It is never changed on the server.
 * At the shatter moment the captured scene is hidden and angular stone meshes
 * burst outward. Each mesh lives for one second and then vanishes.
 */
public final class TimeEraseFractureEffect {
    private static final int RADIUS = 20;
    private static final int BAND_COUNT = 4;
    private static final int BAND_RADIUS = RADIUS / BAND_COUNT;
    private static final int MAX_BLOCKS = 1800;
    private static final int[] BAND_CAPS = {140, 340, 550, 770};
    private static final int OBJECT_SCAN_ABOVE = 32;
    private static final int OBJECT_SCAN_BELOW = 24;

    private static final int SHATTER_TICK = 46;
    private static final int CAMERA_SHAKE_END_TICK = 64;
    private static final int CUSTOM_THEME_TICK = 84;
    private static final int ROCK_LIFETIME_TICKS = 20;
    private static final int BIG_ROCK_COUNT = 13;
    private static final int MEDIUM_ROCK_COUNT = 4;
    private static final int SMALL_ROCK_COUNT = 4;

    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private static final float[][] TRIANGLE_UV = {
            {0.08F, 0.08F}, {0.92F, 0.08F}, {0.50F, 0.92F}
    };
    private static final RenderType ROCK_RENDER_TYPE =
            RenderType.entityTranslucent(TextureAtlas.LOCATION_BLOCKS);

    private static final List<BlockRecord> BLOCKS = new ArrayList<>(MAX_BLOCKS);
    private static final List<Rock> ROCKS =
            new ArrayList<>(BIG_ROCK_COUNT + MEDIUM_ROCK_COUNT + SMALL_ROCK_COUNT);

    private static boolean previousAbilityActive;
    private static boolean effectActive;
    private static boolean customThemePlayed;
    private static boolean rocksCreated;
    private static int elapsedTicks;
    private static ClientLevel capturedLevel;
    private static double centerX;
    private static double centerY;
    private static double centerZ;
    private static double forwardX;
    private static double forwardZ;
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
                ROCKS.clear();
            } else {
                elapsedTicks++;

                // Capture one concentric ring per tick, avoiding a single large
                // 20-block-radius world scan on the activation frame.
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
        ROCKS.clear();
        capturedLevel = level;
        centerX = player.getX();
        centerY = player.getY();
        centerZ = player.getZ();

        float yaw = player.getYRot();
        double yawRadians = Math.toRadians(yaw);
        forwardX = -Math.sin(yawRadians);
        forwardZ = Math.cos(yawRadians);

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
        int capacity = BAND_CAPS[bandIndex];
        Random random = new Random((((long) cx) << 32) ^ (((long) cz) << 1)
                ^ cy ^ (0x4B435246L + bandIndex * 0x9E3779B9L));

        // Keep the ground surface for every column first. Remaining capacity
        // is reserved for extra exposed models such as trunks, leaves, plants,
        // cliff sides and nearby structures.
        List<BlockSnapshot> surfaceSnapshots = new ArrayList<>(capacity);
        List<BlockSnapshot> objectReservoir = new ArrayList<>(capacity);
        int seenObjects = 0;
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
                            || state.getRenderShape() == RenderShape.INVISIBLE
                            || !hasExposedFace(level, x, y, z)) {
                        continue;
                    }

                    seenObjects++;
                    BlockSnapshot snapshot = new BlockSnapshot(x, y, z, state);
                    if (objectReservoir.size() < capacity) {
                        objectReservoir.add(snapshot);
                    } else {
                        int replace = random.nextInt(seenObjects);
                        if (replace < capacity) objectReservoir.set(replace, snapshot);
                    }
                }
            }
        }

        int added = 0;
        for (BlockSnapshot snapshot : surfaceSnapshots) {
            if (BLOCKS.size() >= MAX_BLOCKS || added >= capacity) break;
            BLOCKS.add(new BlockRecord(level, snapshot));
            added++;
        }

        int extraCapacity = Math.max(0, capacity - added);
        for (int i = 0; i < objectReservoir.size() && i < extraCapacity; i++) {
            if (BLOCKS.size() >= MAX_BLOCKS) break;
            BLOCKS.add(new BlockRecord(level, objectReservoir.get(i)));
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
     * Creates irregular faceted stone fragments: thirteen large diamond-like
     * pieces, four roughly one-block angular pieces and four half-block pieces.
     * Spawn anchors in the forward four-block camera safety zone are rejected.
     */
    private static void createRocks() {
        ROCKS.clear();
        if (BLOCKS.isEmpty()) return;

        Random random = new Random(
                (((long) Math.floor(centerX)) << 32)
                        ^ ((long) Math.floor(centerZ))
                        ^ 0x4B43524F434BL
        );
        List<BlockRecord> usedAnchors = new ArrayList<>(
                BIG_ROCK_COUNT + MEDIUM_ROCK_COUNT + SMALL_ROCK_COUNT
        );

        for (int i = 0; i < BIG_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchor(random, usedAnchors, 3.0D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            addRock(anchor, random,
                    1.45F + random.nextFloat() * 0.80F,
                    0.14F + random.nextFloat() * 0.10F,
                    0.23F + random.nextFloat() * 0.13F,
                    false);
        }

        for (int i = 0; i < MEDIUM_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchor(random, usedAnchors, 1.55D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            addRock(anchor, random,
                    0.62F + random.nextFloat() * 0.16F,
                    0.15F + random.nextFloat() * 0.09F,
                    0.27F + random.nextFloat() * 0.12F,
                    true);
        }

        for (int i = 0; i < SMALL_ROCK_COUNT; i++) {
            BlockRecord anchor = pickAnchor(random, usedAnchors, 0.95D);
            if (anchor == null) break;
            usedAnchors.add(anchor);
            addRock(anchor, random,
                    0.29F + random.nextFloat() * 0.10F,
                    0.17F + random.nextFloat() * 0.09F,
                    0.30F + random.nextFloat() * 0.12F,
                    true);
        }
    }

    private static BlockRecord pickAnchor(Random random, List<BlockRecord> used, double minSpacing) {
        double minSpacingSquared = minSpacing * minSpacing;
        for (int attempt = 0; attempt < 900; attempt++) {
            BlockRecord candidate = BLOCKS.get(random.nextInt(BLOCKS.size()));
            if (used.contains(candidate)) continue;

            double dx = candidate.x + 0.5D - centerX;
            double dz = candidate.z + 0.5D - centerZ;
            double distanceSquared = dx * dx + dz * dz;
            double forwardDistance = dx * forwardX + dz * forwardZ;

            // No large or small fragment may spawn within four horizontal
            // blocks in front of the player's initial view direction.
            if (forwardDistance > 0.0D && distanceSquared < 16.0D) continue;

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
        return null;
    }

    private static void addRock(BlockRecord anchor, Random random,
                                float scale, float horizontalSpeed, float verticalSpeed,
                                boolean beveledShape) {
        double dx = anchor.x + 0.5D - centerX;
        double dz = anchor.z + 0.5D - centerZ;
        double length = Math.sqrt(dx * dx + dz * dz);
        float directionX = length > 0.001D ? (float) (dx / length) : 0.0F;
        float directionZ = length > 0.001D ? (float) (dz / length) : 0.0F;

        float velocityX = directionX * horizontalSpeed
                + (random.nextFloat() - 0.5F) * 0.09F;
        float velocityZ = directionZ * horizontalSpeed
                + (random.nextFloat() - 0.5F) * 0.09F;
        float velocityY = verticalSpeed;

        ROCKS.add(new Rock(
                anchor.x + 0.5F,
                anchor.y + 0.45F,
                anchor.z + 0.5F,
                velocityX,
                velocityY,
                velocityZ,
                scale,
                random.nextInt(4),
                random.nextFloat() * 360.0F,
                (random.nextBoolean() ? 1.0F : -1.0F) * (1.0F + random.nextFloat() * 2.0F),
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.8F + random.nextFloat() * 1.8F),
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.7F + random.nextFloat() * 1.5F),
                (random.nextBoolean() ? 1.0F : -1.0F) * (0.6F + random.nextFloat() * 1.4F),
                createRockMesh(random, beveledShape)
        ));
    }

    /**
     * Large rocks use an uneven diamond/rhombus polyhedron. The medium and
     * small pieces use a chipped, bevelled block silhouette: squarer, but with
     * slanted faces and irregular corners rather than a plain cube.
     */
    private static RockMesh createRockMesh(Random random, boolean beveledShape) {
        if (beveledShape) {
            float[][] vertices = new float[8][3];
            for (int i = 0; i < 4; i++) {
                float angle = (float) (Math.PI / 4.0D) + i * ((float) Math.PI / 2.0F);
                float topRadiusX = 0.43F + random.nextFloat() * 0.12F;
                float topRadiusZ = 0.42F + random.nextFloat() * 0.13F;
                float bottomRadiusX = 0.49F + random.nextFloat() * 0.10F;
                float bottomRadiusZ = 0.47F + random.nextFloat() * 0.12F;
                vertices[i] = new float[] {
                        (float) Math.cos(angle) * topRadiusX,
                        0.27F + (random.nextFloat() - 0.5F) * 0.16F,
                        (float) Math.sin(angle) * topRadiusZ
                };
                vertices[i + 4] = new float[] {
                        (float) Math.cos(angle + 0.10F) * bottomRadiusX,
                        -0.34F - random.nextFloat() * 0.13F,
                        (float) Math.sin(angle + 0.10F) * bottomRadiusZ
                };
            }

            int[][] faces = new int[12][3];
            faces[0] = new int[] {0, 1, 2};
            faces[1] = new int[] {0, 2, 3};
            faces[2] = new int[] {4, 6, 5};
            faces[3] = new int[] {4, 7, 6};
            int face = 4;
            for (int i = 0; i < 4; i++) {
                int next = (i + 1) % 4;
                faces[face++] = new int[] {i, i + 4, next + 4};
                faces[face++] = new int[] {i, next + 4, next};
            }
            return new RockMesh(vertices, faces);
        }

        float[][] vertices = new float[8][3];
        vertices[0] = new float[] {
                (random.nextFloat() - 0.5F) * 0.22F,
                0.76F + random.nextFloat() * 0.18F,
                (random.nextFloat() - 0.5F) * 0.22F
        };

        float angleOffset = random.nextFloat() * (float) (Math.PI * 2.0D);
        for (int i = 0; i < 6; i++) {
            float angle = angleOffset + i * ((float) Math.PI / 3.0F);
            float radiusX = 0.53F + random.nextFloat() * 0.23F;
            float radiusZ = 0.48F + random.nextFloat() * 0.26F;
            float y = (random.nextFloat() - 0.5F) * 0.52F;
            vertices[i + 1] = new float[] {
                    (float) Math.cos(angle) * radiusX,
                    y,
                    (float) Math.sin(angle) * radiusZ
            };
        }

        vertices[7] = new float[] {
                (random.nextFloat() - 0.5F) * 0.26F,
                -0.68F - random.nextFloat() * 0.16F,
                (random.nextFloat() - 0.5F) * 0.26F
        };

        int[][] faces = new int[12][3];
        for (int i = 0; i < 6; i++) {
            int current = i + 1;
            int next = ((i + 1) % 6) + 1;
            faces[i] = new int[] {0, current, next};
            faces[i + 6] = new int[] {7, next, current};
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

            float alpha = age <= 15.0F ? 1.0F : (ROCK_LIFETIME_TICKS - age) / 5.0F;
            int alphaByte = (int) (Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F);

            double x = rock.x + rock.velocityX * age;
            double y = rock.y + rock.velocityY * age;
            double z = rock.z + rock.velocityZ * age;
            if (!isInView(x, y, z, camera)) continue;

            poseStack.pushPose();
            poseStack.translate(x - camera.x, y - camera.y, z - camera.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(rock.yaw + age * rock.yawSpeed));
            poseStack.mulPose(Axis.XP.rotationDegrees(rock.pitch + age * rock.pitchSpeed));
            poseStack.mulPose(Axis.ZP.rotationDegrees(rock.roll + age * rock.rollSpeed));
            poseStack.scale(rock.scale, rock.scale, rock.scale);

            Matrix4f matrix = poseStack.last().pose();
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

                float faceCenterX = (a[0] + b[0] + c[0]) / 3.0F;
                float faceCenterY = (a[1] + b[1] + c[1]) / 3.0F;
                float faceCenterZ = (a[2] + b[2] + c[2]) / 3.0F;
                if (nx * faceCenterX + ny * faceCenterY + nz * faceCenterZ < 0.0F) {
                    float[] swap = b;
                    b = c;
                    c = swap;
                    nx = -nx;
                    ny = -ny;
                    nz = -nz;
                }

                float diffuse = Math.max(0.0F, nx * -0.38F + ny * 0.84F + nz * 0.38F);
                float shade = 0.30F + diffuse * 0.70F;
                float variation = 0.84F + ((face[0] * 17 + face[1] * 11 + face[2] * 7) & 7) * 0.018F;
                int color = (int) (235.0F * Math.min(1.0F, shade * variation));

                float[][] triangle = {a, b, c};
                for (int vertex = 0; vertex < 3; vertex++) {
                    float[] point = triangle[vertex];
                    float[] uv = TRIANGLE_UV[vertex];
                    consumer.vertex(matrix, point[0], point[1], point[2])
                            .color(color, color, color, alphaByte)
                            .uv(stoneSprite.getU(uv[0]), stoneSprite.getV(uv[1]))
                            .overlayCoords(OverlayTexture.NO_OVERLAY)
                            .uv2(LightTexture.FULL_BRIGHT)
                            .normal(nx, ny, nz)
                            .endVertex();
                }
            }

            poseStack.popPose();
        }

        buffer.endBatch(ROCK_RENDER_TYPE);
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
