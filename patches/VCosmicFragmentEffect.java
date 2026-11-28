package net.hydra.jojomod.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Client-only visual effect played when the ability-four key (V by default) is pressed.
 *
 * Timeline, in seconds of real client time from the key press:
 *   1.10 -> 1.30  a very wide bright-pink band sweeps bottom to top over the whole screen.
 *   2.00          the nearby blocks break apart into a small number of large, differently shaped
 *                 debris chunks (a few huge ones, some small ones). They stay exactly where they
 *                 were: only a hairline gap opens up, plus a barely visible hover.
 *   2.60          each chunk cracks in two and the whole screen shakes (gently).
 *   4.00 -> 4.90  the debris shrinks, fades and drifts up, leaving only the starry cosmos.
 *
 * Debris is built from clusters of neighbouring blocks of the same block type, so a single chunk
 * can be several blocks across instead of being one scaled-down cube. Every chunk is a cut, sheared
 * and corner-folded piece of that volume, textured with the block's own sprite.
 *
 * Nothing here writes to the world: block states are only read, no packets are sent, and collision,
 * entities and ability logic are untouched.
 */
public final class VCosmicFragmentEffect {
    /* ---- timeline, in seconds ---- */
    private static final float STRIPE_START = 1.10F;
    private static final float STRIPE_END = 1.30F;
    private static final float SHATTER_TIME = 2.00F;
    private static final float SHAKE_TIME = 2.60F;
    private static final float SHAKE_HOLD_END = 3.55F;
    private static final float DISSOLVE_TIME = 4.00F;
    private static final float END_TIME = 4.90F;

    /* ---- snapshot bounds ---- */
    private static final int RADIUS = 15;
    private static final int VERTICAL = 15;
    /** Safety net only; a normal 15-block snapshot stays below this. */
    private static final int HARD_LIMIT = 6000;
    /** Upper size of one debris cluster, in blocks: this is what makes a chunk look huge. */
    private static final int MAX_CLUSTER = 44;

    private static final List<Cluster> CLUSTERS = new ArrayList<>();

    private static boolean active;
    private static boolean keyWasDown;
    private static boolean cosmosAnchored;
    private static long startMillis;
    private static Level capturedLevel;
    private static double originX;
    private static double originY;
    private static double originZ;

    private static boolean shakeApplied;
    private static float shakeDx;
    private static float shakeDy;

    private VCosmicFragmentEffect() {
    }

    /* ------------------------------------------------------------------ */
    /* world rendering                                                     */
    /* ------------------------------------------------------------------ */

    public static void renderWorld(PoseStack poseStack, MultiBufferSource buffer,
                                  Camera camera, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();

        boolean down = minecraft.screen == null
                && KeyInputRegistry.abilityFourKey != null
                && KeyInputRegistry.abilityFourKey.isDown();
        if (down && !keyWasDown) {
            start(minecraft);
        }
        keyWasDown = down;

        if (!active) {
            return;
        }
        if (minecraft.player == null || minecraft.level == null
                || capturedLevel != minecraft.level) {
            stop();
            return;
        }

        float time = elapsedSeconds();
        if (time >= END_TIME) {
            stop();
            return;
        }
        if (cosmosAnchored && time > SHATTER_TIME && !ClientUtil.isUsingTimeErase) {
            stop();
            return;
        }
        if (CLUSTERS.isEmpty()) {
            return;
        }

        Vec3 cameraPos = camera.getPosition();
        Vec3 view = minecraft.player.getViewVector(partialTick);

        if (time < SHATTER_TIME) {
            if (worldHidden()) {
                renderIntact(poseStack, buffer, cameraPos, view);
            }
            return;
        }

        if (time < SHAKE_TIME) {
            renderWholeChunks(poseStack, buffer, cameraPos, view, time);
        } else {
            renderBrokenChunks(poseStack, buffer, cameraPos, view, time);
        }
    }

    private static boolean worldHidden() {
        return ClientUtil.isUsingTimeErase && ClientUtil.TimeErase >= ClientUtil.fadeTime;
    }

    private static boolean behindCamera(Vec3 cameraPos, Vec3 view, double x, double y, double z) {
        double dx = x - cameraPos.x;
        double dy = y - cameraPos.y;
        double dz = z - cameraPos.z;
        return dx * view.x + dy * view.y + dz * view.z < -1.5D;
    }

    private static void renderIntact(PoseStack poseStack, MultiBufferSource buffer, Vec3 cameraPos,
                                     Vec3 view) {
        Minecraft minecraft = Minecraft.getInstance();
        for (Cluster cluster : CLUSTERS) {
            for (Piece piece : cluster.pieces) {
                if (behindCamera(cameraPos, view, piece.cx, piece.cy, piece.cz)) {
                    continue;
                }
                for (int[] cell : piece.cells) {
                    poseStack.pushPose();
                    poseStack.translate(
                            cell[0] - cameraPos.x,
                            cell[1] - cameraPos.y,
                            cell[2] - cameraPos.z
                    );
                    minecraft.getBlockRenderer().renderSingleBlock(
                            cluster.state,
                            poseStack,
                            buffer,
                            LightTexture.FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY
                    );
                    poseStack.popPose();
                }
            }
        }
    }

    /** 2.00 -> 2.60 s: the debris is already broken up, but it stays in place. */
    private static void renderWholeChunks(PoseStack poseStack, MultiBufferSource buffer,
                                          Vec3 cameraPos, Vec3 view, float time) {
        float spread = smooth((time - SHATTER_TIME) / (SHAKE_TIME - SHATTER_TIME));

        for (Cluster cluster : CLUSTERS) {
            for (Piece piece : cluster.pieces) {
                if (behindCamera(cameraPos, view, piece.cx, piece.cy, piece.cz)) {
                    continue;
                }
                // A hairline gap and a slow hover; the chunk does not travel anywhere.
                float bob = 0.012F * Mth.sin(time * 1.9F + piece.phase);

                poseStack.pushPose();
                poseStack.translate(
                        (float) (piece.cx + piece.outX * spread + bob * 0.35F - cameraPos.x),
                        (float) (piece.cy + piece.outY * spread + bob - cameraPos.y),
                        (float) (piece.cz + piece.outZ * spread - cameraPos.z)
                );
                poseStack.mulPose(Axis.XP.rotationDegrees(piece.rx * spread));
                poseStack.mulPose(Axis.YP.rotationDegrees(piece.ry * spread));
                poseStack.mulPose(Axis.ZP.rotationDegrees(piece.rz * spread));
                emitPiece(buffer, poseStack, cluster.sprite, piece, 1.0F);
                poseStack.popPose();
            }
        }
    }

    /** 2.60 -> 4.90 s: every chunk cracks in two, then the debris dissolves. */
    private static void renderBrokenChunks(PoseStack poseStack, MultiBufferSource buffer,
                                          Vec3 cameraPos, Vec3 view, float time) {
        float split = smooth((time - SHAKE_TIME) / 0.30F);
        float dissolve = smooth((time - DISSOLVE_TIME) / (END_TIME - DISSOLVE_TIME));
        float alpha = 1.0F - dissolve;
        float rise = dissolve * 0.35F;
        float scale = 1.0F - 0.90F * dissolve;

        if (alpha <= 0.02F) {
            return;
        }

        float residual = 1.0F - Mth.clamp((time - SHAKE_TIME) / 0.14F, 0.0F, 1.0F);
        if (residual > 0.02F) {
            for (Cluster cluster : CLUSTERS) {
                for (Piece piece : cluster.pieces) {
                    if (behindCamera(cameraPos, view, piece.cx, piece.cy, piece.cz)) {
                        continue;
                    }
                    poseStack.pushPose();
                    poseStack.translate(
                            (float) (piece.cx + piece.outX - cameraPos.x),
                            (float) (piece.cy + piece.outY - cameraPos.y),
                            (float) (piece.cz + piece.outZ - cameraPos.z)
                    );
                    poseStack.scale(residual, residual, residual);
                    poseStack.mulPose(Axis.XP.rotationDegrees(piece.rx));
                    poseStack.mulPose(Axis.YP.rotationDegrees(piece.ry));
                    poseStack.mulPose(Axis.ZP.rotationDegrees(piece.rz));
                    emitPiece(buffer, poseStack, cluster.sprite, piece, 1.0F);
                    poseStack.popPose();
                }
            }
        }

        for (Cluster cluster : CLUSTERS) {
            for (Piece piece : cluster.pieces) {
                if (behindCamera(cameraPos, view, piece.cx, piece.cy, piece.cz)) {
                    continue;
                }
                for (Piece child : piece.children) {
                    if (behindCamera(cameraPos, view, child.cx, child.cy, child.cz)) {
                        continue;
                    }
                    float drift = 0.045F * split;
                    poseStack.pushPose();
                    poseStack.translate(
                            (float) (child.cx + child.driftX * drift - cameraPos.x),
                            (float) (child.cy + child.driftY * drift + rise - cameraPos.y),
                            (float) (child.cz + child.driftZ * drift - cameraPos.z)
                    );
                    poseStack.scale(scale, scale, scale);
                    poseStack.mulPose(Axis.XP.rotationDegrees(child.rx * 1.6F * split));
                    poseStack.mulPose(Axis.YP.rotationDegrees(child.ry * 1.6F * split));
                    poseStack.mulPose(Axis.ZP.rotationDegrees(child.rz * 1.6F * split));
                    emitPiece(buffer, poseStack, cluster.sprite, child, alpha);
                    poseStack.popPose();
                }
            }
        }
    }

    /* ---- chunk geometry ---- */

    /**
     * Draws one debris chunk: the six faces of a cut, sheared and corner-folded box, textured with
     * the block sprite. Faces are split per block cell so the texture keeps tiling across a chunk
     * that is several blocks wide.
     */
    private static void emitPiece(MultiBufferSource buffer, PoseStack poseStack,
                                 TextureAtlasSprite sprite, Piece piece, float alpha) {
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(sprite.atlasLocation()));
        Matrix4f matrix = poseStack.last().pose();
        Matrix3f normalMatrix = poseStack.last().normal();

        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();
        int light = LightTexture.FULL_BRIGHT;

        float[] c = piece.corners;
        for (int face = 0; face < 6; face++) {
            int a = FACE_A[face];
            int b = FACE_B[face];
            int d = FACE_D[face];
            int e = FACE_E[face];
            float shade = FACE_SHADE[face];
            float nx = FACE_NORMAL[face * 3];
            float ny = FACE_NORMAL[face * 3 + 1];
            float nz = FACE_NORMAL[face * 3 + 2];
            int cellsU = piece.cellsU[face];
            int cellsV = piece.cellsV[face];

            for (int iu = 0; iu < cellsU; iu++) {
                float uA = iu / (float) cellsU;
                float uB = (iu + 1) / (float) cellsU;
                for (int iv = 0; iv < cellsV; iv++) {
                    float vA = iv / (float) cellsV;
                    float vB = (iv + 1) / (float) cellsV;

                    vertex(consumer, matrix, normalMatrix, c, a, b, d, e, piece,
                            uA, vA, u0, u1, v0, v1, shade, alpha, nx, ny, nz, light);
                    vertex(consumer, matrix, normalMatrix, c, a, b, d, e, piece,
                            uB, vA, u0, u1, v0, v1, shade, alpha, nx, ny, nz, light);
                    vertex(consumer, matrix, normalMatrix, c, a, b, d, e, piece,
                            uB, vB, u0, u1, v0, v1, shade, alpha, nx, ny, nz, light);
                    vertex(consumer, matrix, normalMatrix, c, a, b, d, e, piece,
                            uA, vB, u0, u1, v0, v1, shade, alpha, nx, ny, nz, light);
                }
            }
        }
    }

    /** Emits one vertex of a face cell, bilinearly interpolated across the four face corners. */
    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix,
                               float[] corners, int a, int b, int d, int e, Piece piece,
                               float u, float v, float u0, float u1, float v0, float v1,
                               float shade, float alpha, float nx, float ny, float nz, int light) {
        float wa = (1.0F - u) * (1.0F - v);
        float wb = u * (1.0F - v);
        float wd = u * v;
        float we = (1.0F - u) * v;

        float x = corners[a * 3] * wa + corners[b * 3] * wb
                + corners[d * 3] * wd + corners[e * 3] * we - piece.cx;
        float y = corners[a * 3 + 1] * wa + corners[b * 3 + 1] * wb
                + corners[d * 3 + 1] * wd + corners[e * 3 + 1] * we - piece.cy;
        float z = corners[a * 3 + 2] * wa + corners[b * 3 + 2] * wb
                + corners[d * 3 + 2] * wd + corners[e * 3 + 2] * we - piece.cz;

        consumer.vertex(matrix, x, y, z)
                .color(shade, shade, shade, alpha)
                .uv(u0 + (u1 - u0) * u, v0 + (v1 - v0) * (1.0F - v))
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normalMatrix, nx, ny, nz)
                .endVertex();
    }

    /* ------------------------------------------------------------------ */
    /* screen shake                                                        */
    /* ------------------------------------------------------------------ */

    /** Called at the head of the level render, where the pose is still view aligned. */
    public static void applyScreenShake(PoseStack poseStack) {
        shakeApplied = false;
        shakeDx = 0.0F;
        shakeDy = 0.0F;
        if (!active) {
            return;
        }
        float time = elapsedSeconds();
        if (time < SHAKE_TIME || time > DISSOLVE_TIME) {
            return;
        }
        float amplitude;
        if (time < SHAKE_HOLD_END) {
            amplitude = 0.038F * Mth.clamp((time - SHAKE_TIME) / 0.15F, 0.0F, 1.0F);
        } else {
            amplitude = 0.038F * (1.0F - Mth.clamp(
                    (time - SHAKE_HOLD_END) / (DISSOLVE_TIME - SHAKE_HOLD_END), 0.0F, 1.0F));
        }
        if (amplitude <= 0.0004F) {
            return;
        }

        long millis = Util.getMillis();
        shakeDx = amplitude * Mth.sin(millis * 0.071F);
        shakeDy = amplitude * 0.8F * Mth.sin(millis * 0.093F + 1.9F);
        poseStack.translate(shakeDx, shakeDy, 0.0F);
        shakeApplied = true;
    }

    /** Called at the tail of the level render to undo the shake offset. */
    public static void clearScreenShake(PoseStack poseStack) {
        if (shakeApplied) {
            poseStack.translate(-shakeDx, -shakeDy, 0.0F);
            shakeApplied = false;
        }
    }

    /* ------------------------------------------------------------------ */
    /* screen overlay: the wide pink sweep                                 */
    /* ------------------------------------------------------------------ */

    public static void renderScreenOverlay(GuiGraphics graphics) {
        if (!active) {
            return;
        }
        float time = elapsedSeconds();
        if (time < STRIPE_START || time > STRIPE_END) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        float progress = (time - STRIPE_START) / (STRIPE_END - STRIPE_START);
        float bandHeight = height * 0.40F;
        float top = height - progress * (height + bandHeight);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        int slices = 32;
        for (int i = 0; i < slices; i++) {
            float u = (i + 0.5F) / slices;
            float profile = (float) Math.pow(Mth.sin(u * (float) Math.PI), 0.55F);
            int alpha = (int) Mth.clamp(profile * 245.0F, 0.0F, 255.0F);
            if (alpha <= 2) {
                continue;
            }
            int red = 255;
            int green = (int) (55.0F + 95.0F * profile);
            int blue = (int) (128.0F + 78.0F * profile);
            int color = (alpha << 24) | (red << 16) | (green << 8) | blue;

            int y0 = Mth.floor(top + (i / (float) slices) * bandHeight);
            int y1 = (int) Math.ceil(top + ((i + 1) / (float) slices) * bandHeight);
            if (y1 <= 0 || y0 >= height) {
                continue;
            }
            graphics.fill(0, Math.max(0, y0), width, Math.min(height, y1), color);
        }

        RenderSystem.disableBlend();
    }

    /* ------------------------------------------------------------------ */
    /* lifecycle                                                           */
    /* ------------------------------------------------------------------ */

    private static void start(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        capturedLevel = minecraft.level;
        originX = minecraft.player.getX();
        originY = minecraft.player.getY();
        originZ = minecraft.player.getZ();
        cosmosAnchored = ClientUtil.isUsingTimeErase;
        startMillis = Util.getMillis();
        shakeApplied = false;
        active = true;
        capture(capturedLevel);
    }

    public static void stop() {
        active = false;
        keyWasDown = false;
        cosmosAnchored = false;
        capturedLevel = null;
        shakeApplied = false;
        CLUSTERS.clear();
    }

    private static float elapsedSeconds() {
        return (Util.getMillis() - startMillis) / 1000.0F;
    }

    private static float smooth(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    /* ------------------------------------------------------------------ */
    /* snapshot: clusters of neighbouring blocks                           */
    /* ------------------------------------------------------------------ */

    private static void capture(Level level) {
        CLUSTERS.clear();

        int centerX = Mth.floor(originX);
        int centerY = Mth.floor(originY);
        int centerZ = Mth.floor(originZ);
        int minY = Math.max(level.getMinBuildHeight(), centerY - VERTICAL);
        int maxY = Math.min(level.getMaxBuildHeight() - 1, centerY + VERTICAL);

        Map<Long, BlockState> found = new HashMap<>();
        int limit = RADIUS * RADIUS + RADIUS;

        for (int x = centerX - RADIUS; x <= centerX + RADIUS; x++) {
            int dx = x - centerX;
            for (int z = centerZ - RADIUS; z <= centerZ + RADIUS; z++) {
                int dz = z - centerZ;
                int flat = dx * dx + dz * dz;
                if (flat > RADIUS * RADIUS) {
                    continue;
                }
                for (int y = minY; y <= maxY; y++) {
                    int dy = y - centerY;
                    if (dy * dy + flat > limit) {
                        continue;
                    }
                    if (found.size() >= HARD_LIMIT) {
                        break;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(pos);
                    if (!isCrackable(state)) {
                        continue;
                    }
                    if (!isExposed(level, pos)) {
                        continue;
                    }
                    found.put(pos.asLong(), state);
                }
            }
        }

        Random random = new Random((((long) centerX) << 32) ^ (((long) centerZ) << 7)
                ^ centerY ^ 0x4B435633L);
        Set<Long> visited = new HashSet<>();

        for (Map.Entry<Long, BlockState> entry : found.entrySet()) {
            long seed = entry.getKey();
            if (visited.contains(seed)) {
                continue;
            }
            BlockState state = entry.getValue();
            List<BlockPos> cells = new ArrayList<>();
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(seed);
            visited.add(seed);

            while (!queue.isEmpty() && cells.size() < MAX_CLUSTER) {
                long key = queue.poll();
                BlockPos pos = BlockPos.of(key);
                cells.add(pos);
                for (int face = 0; face < 6; face++) {
                    BlockPos neighbour = pos.offset(
                            face == 0 ? 1 : (face == 1 ? -1 : 0),
                            face == 2 ? 1 : (face == 3 ? -1 : 0),
                            face == 4 ? 1 : (face == 5 ? -1 : 0)
                    );
                    long neighbourKey = neighbour.asLong();
                    if (visited.contains(neighbourKey)) {
                        continue;
                    }
                    BlockState neighbourState = found.get(neighbourKey);
                    if (neighbourState != null && neighbourState == state) {
                        visited.add(neighbourKey);
                        queue.add(neighbourKey);
                    }
                }
            }

            if (!cells.isEmpty()) {
                buildCluster(state, cells, random);
            }
        }
    }

    private static void buildCluster(BlockState state, List<BlockPos> cells, Random random) {
        Cluster cluster = new Cluster(state, spriteOf(state));

        float clusterX = 0.0F;
        float clusterY = 0.0F;
        float clusterZ = 0.0F;
        for (BlockPos pos : cells) {
            clusterX += pos.getX() + 0.5F;
            clusterY += pos.getY() + 0.5F;
            clusterZ += pos.getZ() + 0.5F;
        }
        clusterX /= cells.size();
        clusterY /= cells.size();
        clusterZ /= cells.size();

        int pieceCount = cells.size() <= 12 ? 1 : (cells.size() <= 26 ? 2 : 3);
        for (List<BlockPos> group : partition(cells, pieceCount, random)) {
            Piece piece = makePiece(group, random);
            if (piece == null) {
                continue;
            }
            piece.outX = (piece.cx - clusterX) * 0.10F;
            piece.outY = (piece.cy - clusterY) * 0.10F;
            piece.outZ = (piece.cz - clusterZ) * 0.10F;

            List<List<BlockPos>> halves = partition(group, 2, random);
            if (halves.size() < 2) {
                // A one-block chunk still has to crack in two: cut its own volume instead.
                for (float[] halfBox : splitBox(boundsOf(group), random)) {
                    addChild(piece, makePieceFromBox(halfBox, random), random);
                }
            } else {
                for (List<BlockPos> half : halves) {
                    addChild(piece, makePiece(half, random), random);
                }
            }
            cluster.pieces.add(piece);
        }

        if (!cluster.pieces.isEmpty()) {
            CLUSTERS.add(cluster);
        }
    }

    private static void addChild(Piece parent, Piece child, Random random) {
        if (child == null) {
            return;
        }
        child.outX = (child.cx - parent.cx) * 0.18F;
        child.outY = (child.cy - parent.cy) * 0.18F;
        child.outZ = (child.cz - parent.cz) * 0.18F;
        child.driftX = randomBetween(random, -1.0F, 1.0F);
        child.driftY = randomBetween(random, -0.3F, 1.0F);
        child.driftZ = randomBetween(random, -1.0F, 1.0F);
        float length = Math.max(0.001F, (float) Math.sqrt(
                child.driftX * child.driftX + child.driftY * child.driftY
                + child.driftZ * child.driftZ));
        child.driftX /= length;
        child.driftY /= length;
        child.driftZ /= length;
        parent.children.add(child);
    }

    /** Splits cells into contiguous groups along the longest axis, so pieces stay chunky. */
    private static List<List<BlockPos>> partition(List<BlockPos> cells, int count, Random random) {
        List<List<BlockPos>> groups = new ArrayList<>();
        if (count <= 1 || cells.size() <= 1) {
            groups.add(new ArrayList<>(cells));
            return groups;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : cells) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        int sx = maxX - minX;
        int sy = maxY - minY;
        int sz = maxZ - minZ;
        int axis = sx >= sy && sx >= sz ? 0 : (sy >= sz ? 1 : 2);

        List<BlockPos> sorted = new ArrayList<>(cells);
        final int sortAxis = axis;
        sorted.sort(Comparator.comparingInt((BlockPos pos) -> sortAxis == 0 ? pos.getX()
                : (sortAxis == 1 ? pos.getY() : pos.getZ())));

        int perGroup = Math.max(1, Math.round(sorted.size() / (float) count));
        for (int i = 0; i < sorted.size(); i += perGroup) {
            groups.add(new ArrayList<>(sorted.subList(i, Math.min(sorted.size(), i + perGroup))));
        }
        return groups;
    }

    private static float[] boundsOf(List<BlockPos> cells) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : cells) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        return new float[]{minX, minY, minZ, maxX + 1.0F, maxY + 1.0F, maxZ + 1.0F};
    }

    private static float[][] splitBox(float[] box, Random random) {
        int axis = random.nextInt(3);
        float t = 0.36F + random.nextFloat() * 0.28F;
        float[] first = box.clone();
        float[] second = box.clone();
        if (axis == 0) {
            float cut = box[0] + (box[3] - box[0]) * t;
            first[3] = cut;
            second[0] = cut;
        } else if (axis == 1) {
            float cut = box[1] + (box[4] - box[1]) * t;
            first[4] = cut;
            second[1] = cut;
        } else {
            float cut = box[2] + (box[5] - box[2]) * t;
            first[5] = cut;
            second[2] = cut;
        }
        return new float[][]{first, second};
    }

    private static Piece makePiece(List<BlockPos> cells, Random random) {
        if (cells == null || cells.isEmpty()) {
            return null;
        }
        Piece piece = makePieceFromBox(boundsOf(cells), random);
        for (BlockPos cell : cells) {
            piece.cells.add(new int[]{cell.getX(), cell.getY(), cell.getZ()});
        }
        return piece;
    }

    private static Piece makePieceFromBox(float[] box, Random random) {
        float x0 = box[0];
        float y0 = box[1];
        float z0 = box[2];
        float x1 = box[3];
        float y1 = box[4];
        float z1 = box[5];
        float sizeX = x1 - x0;
        float sizeY = y1 - y0;
        float sizeZ = z1 - z0;

        // Jitter and shear scale with the chunk, so a huge chunk stays coherent.
        float smallest = Math.min(sizeX, Math.min(sizeY, sizeZ));
        float jitter = Math.min(0.26F, 0.075F * smallest);
        float shear = Math.min(0.30F, 0.11F * smallest);

        float[] corners = new float[24];
        int index = 0;
        for (int z = 0; z < 2; z++) {
            for (int y = 0; y < 2; y++) {
                for (int x = 0; x < 2; x++) {
                    corners[index * 3] = (x == 0 ? x0 : x1) + randomBetween(random, -jitter, jitter);
                    corners[index * 3 + 1] = (y == 0 ? y0 : y1) + randomBetween(random, -jitter, jitter);
                    corners[index * 3 + 2] = (z == 0 ? z0 : z1) + randomBetween(random, -jitter, jitter);
                    index++;
                }
            }
        }

        float bcx = (x0 + x1) * 0.5F;
        float bcy = (y0 + y1) * 0.5F;
        float bcz = (z0 + z1) * 0.5F;
        float s01 = randomBetween(random, -shear, shear) / Math.max(1.0F, sizeY);
        float s02 = randomBetween(random, -shear, shear) / Math.max(1.0F, sizeZ);
        float s12 = randomBetween(random, -shear, shear) / Math.max(1.0F, sizeZ);
        for (int i = 0; i < 8; i++) {
            float dx = corners[i * 3] - bcx;
            float dy = corners[i * 3 + 1] - bcy;
            float dz = corners[i * 3 + 2] - bcz;
            corners[i * 3] = bcx + dx + s01 * dy + s02 * dz;
            corners[i * 3 + 1] = bcy + dy + s12 * dz;
            corners[i * 3 + 2] = bcz + dz + s12 * dx;
        }

        // Fold corners onto neighbours: quads collapse into triangles, so the chunk is an
        // irregular debris wedge rather than a box.
        int snaps = random.nextFloat() < 0.40F ? 1 : (random.nextFloat() < 0.30F ? 2 : 0);
        for (int snap = 0; snap < snaps; snap++) {
            int from = random.nextInt(8);
            int to = from ^ (1 << random.nextInt(3));
            corners[from * 3] = corners[to * 3];
            corners[from * 3 + 1] = corners[to * 3 + 1];
            corners[from * 3 + 2] = corners[to * 3 + 2];
        }

        Piece piece = new Piece(corners, bcx, bcy, bcz);
        piece.cellsU[0] = cellCount(sizeX);
        piece.cellsV[0] = cellCount(sizeZ);
        piece.cellsU[1] = cellCount(sizeX);
        piece.cellsV[1] = cellCount(sizeZ);
        piece.cellsU[2] = cellCount(sizeX);
        piece.cellsV[2] = cellCount(sizeY);
        piece.cellsU[3] = cellCount(sizeX);
        piece.cellsV[3] = cellCount(sizeY);
        piece.cellsU[4] = cellCount(sizeY);
        piece.cellsV[4] = cellCount(sizeZ);
        piece.cellsU[5] = cellCount(sizeY);
        piece.cellsV[5] = cellCount(sizeZ);

        piece.rx = randomBetween(random, -3.0F, 3.0F);
        piece.ry = randomBetween(random, -3.0F, 3.0F);
        piece.rz = randomBetween(random, -3.0F, 3.0F);
        piece.phase = random.nextFloat() * 6.2832F;
        return piece;
    }

    private static int cellCount(float extent) {
        return Math.max(1, Math.round(extent));
    }

    private static TextureAtlasSprite spriteOf(BlockState state) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.getBlockRenderer().getBlockModelShaper().getBlockModel(state).getParticleIcon();
    }

    /** Surface blocks and trees only: anything with a real model and no fluid. */
    private static boolean isCrackable(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (state.getRenderShape() != RenderShape.MODEL) {
            return false;
        }
        return state.getFluidState().isEmpty();
    }

    private static boolean isExposed(Level level, BlockPos pos) {
        for (int i = 0; i < 6; i++) {
            BlockPos neighbor = pos.offset(
                    i == 0 ? 1 : (i == 1 ? -1 : 0),
                    i == 2 ? 1 : (i == 3 ? -1 : 0),
                    i == 4 ? 1 : (i == 5 ? -1 : 0)
            );
            if (!level.getBlockState(neighbor).canOcclude()) {
                return true;
            }
        }
        return false;
    }

    private static float randomBetween(Random random, float min, float max) {
        return min + random.nextFloat() * (max - min);
    }

    /* ------------------------------------------------------------------ */
    /* data holders                                                        */
    /* ------------------------------------------------------------------ */

    /* Face corner order for a box: bit0 = x, bit1 = y, bit2 = z. */
    private static final int[] FACE_A = {2, 0, 4, 0, 1, 0};
    private static final int[] FACE_B = {3, 1, 5, 1, 3, 2};
    private static final int[] FACE_D = {7, 5, 7, 3, 7, 6};
    private static final int[] FACE_E = {6, 4, 6, 2, 5, 4};
    private static final float[] FACE_SHADE = {1.0F, 0.52F, 0.80F, 0.80F, 0.63F, 0.63F};
    private static final float[] FACE_NORMAL = {
            0, 1, 0,
            0, -1, 0,
            0, 0, 1,
            0, 0, -1,
            1, 0, 0,
            -1, 0, 0
    };

    private static final class Cluster {
        final BlockState state;
        final TextureAtlasSprite sprite;
        final List<Piece> pieces = new ArrayList<>();

        Cluster(BlockState state, TextureAtlasSprite sprite) {
            this.state = state;
            this.sprite = sprite;
        }
    }

    private static final class Piece {
        final float[] corners;
        final float cx;
        final float cy;
        final float cz;
        final int[] cellsU = new int[6];
        final int[] cellsV = new int[6];
        final List<Piece> children = new ArrayList<>();
        final List<int[]> cells = new ArrayList<>();
        float rx;
        float ry;
        float rz;
        float phase;
        float driftX;
        float driftY;
        float driftZ;
        float outX;
        float outY;
        float outZ;

        Piece(float[] corners, float cx, float cy, float cz) {
            this.corners = corners;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
        }
    }
}
