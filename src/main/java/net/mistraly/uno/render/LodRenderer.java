package net.mistraly.uno.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.DeviceFeatures;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Iterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.mistraly.uno.Uno;
import net.mistraly.uno.UnoConfig;
import net.mistraly.uno.mesh.Mesher;
import net.mistraly.uno.world.LodKey;
import net.mistraly.uno.world.LodWorld;
import net.neoforged.neoforge.client.event.PrepareRenderBuffersEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/// Picks which LOD sections to draw each frame and draws them inside the vanilla opaque pass.
/// Render thread only.
public final class LodRenderer {
    private static final int MAX_DRAWS = 32768;
    private static final int INSTANCE_BYTES = 16;
    private static final int COMMAND_BYTES = RenderPass.INDIRECT_INDEXED_DRAW_SIZE;
    private static final int FRAME_UBO_BYTES = 80;
    private static final long UPLOAD_BUDGET = 8L * 1024 * 1024;
    private static final int REQUEST_BUDGET = 256;
    private static final int MAX_PENDING = 2048;

    public static final VertexFormat SECTION_FORMAT = VertexFormat.builder(1).addAttribute("SectionInfo", GpuFormat.RGBA32_SINT).build();
    private static final BindGroupLayout UNO_LAYOUT = BindGroupLayout.builder()
        .withUniform("UnoFrame", UniformType.UNIFORM_BUFFER)
        .withUniform("UnoQuads", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
        .withUniform("UnoMaterials", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
        .build();
    private static final RenderPipeline.Snippet LOD_SNIPPET = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
        .withBindGroupLayout(UNO_LAYOUT)
        .withVertexShader(Identifier.fromNamespaceAndPath(Uno.MOD_ID, "core/lod"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(Uno.MOD_ID, "core/lod"))
        .withVertexBinding(0, SECTION_FORMAT)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withCull(true)
        .buildSnippet();
    public static final RenderPipeline PIPELINE = RenderPipeline.builder(LOD_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Uno.MOD_ID, "pipeline/lod"))
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .build();
    /// Real block models on level-0 LODs: Position, atlas UV, colour (tint x shade), light (block, sky) like vanilla UV2.
    public static final VertexFormat MODEL_FORMAT = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("Color", GpuFormat.RGBA8_UNORM)
        .addAttribute("UV2", GpuFormat.RG16_SINT)
        .build();
    private static final BindGroupLayout MODEL_LAYOUT = BindGroupLayout.builder()
        .withUniform("UnoFrame", UniformType.UNIFORM_BUFFER)
        .build();
    public static final RenderPipeline MODEL_PIPELINE = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Uno.MOD_ID, "pipeline/lod_model"))
        .withBindGroupLayout(BindGroupLayouts.PROJECTION)
        .withBindGroupLayout(BindGroupLayouts.FOG)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
        .withBindGroupLayout(MODEL_LAYOUT)
        .withVertexShader(Identifier.fromNamespaceAndPath(Uno.MOD_ID, "core/lod_model"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(Uno.MOD_ID, "core/lod_model"))
        .withVertexBinding(0, MODEL_FORMAT)
        .withVertexBinding(1, SECTION_FORMAT)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withCull(true)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .build();
    /// Fluid faces: blended over what is behind them, no depth writes.
    public static final RenderPipeline WATER_PIPELINE = RenderPipeline.builder(LOD_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath(Uno.MOD_ID, "pipeline/lod_water"))
        .withShaderDefine("TRANSLUCENT")
        .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, false))
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .build();

    private static final class Node {
        boolean pending;
        boolean known;
        boolean stale;
        int version = -1;
        int quadStart = -1;
        int quadCount;
        int fluidCount;
        int modelStart = -1;
        int modelCount;
        long lastSeen;

        boolean hasGeometry() {
            return this.quadStart >= 0 || this.modelStart >= 0;
        }

        void release() {
            if (this.quadStart >= 0 && arena != null) {
                arena.release(this.quadStart, this.quadCount);
            }
            if (this.modelStart >= 0 && modelArena != null) {
                modelArena.release(this.modelStart, this.modelCount);
            }
            this.quadStart = -1;
            this.quadCount = 0;
            this.fluidCount = 0;
            this.modelStart = -1;
            this.modelCount = 0;
        }
    }

    private static @Nullable LodWorld world;
    private static @Nullable QuadArena arena;
    private static @Nullable QuadArena modelArena;
    private static @Nullable FrameBuffers frame;
    private static @Nullable MaterialTable materials;
    private static final Long2ObjectOpenHashMap<Node> NODES = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<LodWorld.MeshResult> BACKLOG = new ArrayDeque<>();
    private static final LongArrayList DRAWS = new LongArrayList();
    private static long frameIndex;
    private static int pendingCount;
    private static int drawCount;
    private static int waterCount;
    private static int modelDraws;
    private static boolean waterInOpaquePass;
    private static double currentZoom = 1.0;
    private static int maxQuadsPerDraw;
    private static boolean unsupportedLogged;
    private static int minY;
    private static int maxY;

    private LodRenderer() {
    }

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registerPipeline(PIPELINE);
        event.registerPipeline(WATER_PIPELINE);
        event.registerPipeline(MODEL_PIPELINE);
    }

    public static boolean isActive() {
        return world != null && UnoConfig.ENABLED.get();
    }

    /// Far plane the camera needs so LODs are not clipped.
    public static float farPlane() {
        return isActive() ? UnoConfig.LOD_DISTANCE.get() + 512.0F : 0.0F;
    }

    /// Vanilla / Sodium draw chunks out to the render distance; LODs start there. Where both overlap,
    /// the depth bias in the shader lets vanilla win.
    private static int holeRadius() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }

    public static void attach(LodWorld newWorld, int worldMinY, int worldMaxY) {
        detach();
        DeviceFeatures features = RenderSystem.getDevice().getDeviceInfo().features();
        if (!features.drawIndirect() || !features.nonZeroFirstInstance()) {
            if (!unsupportedLogged) {
                Uno.LOGGER.warn("Uno disabled: the graphics device lacks indirect draws or non-zero first instance");
                unsupportedLogged = true;
            }
            newWorld.close();
            return;
        }
        world = newWorld;
        minY = worldMinY;
        maxY = worldMaxY;
        arena = new QuadArena("Uno quads", UnoConfig.GPU_MEMORY_MB.get(), Mesher.QUAD_BYTES, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER);
        modelArena = new QuadArena("Uno model vertices", UnoConfig.MODEL_MEMORY_MB.get(), Mesher.MODEL_QUAD_BYTES, GpuBuffer.USAGE_VERTEX);
        frame = new FrameBuffers();
        materials = new MaterialTable();
    }

    public static void detach() {
        if (world != null) {
            world.close();
            world = null;
        }
        for (LodWorld.MeshResult result : BACKLOG) {
            result.free();
        }
        BACKLOG.clear();
        NODES.clear();
        DRAWS.clear();
        pendingCount = 0;
        drawCount = 0;
        if (arena != null) {
            arena.close();
            arena = null;
        }
        if (modelArena != null) {
            modelArena.close();
            modelArena = null;
        }
        if (frame != null) {
            frame.close();
            frame = null;
        }
        if (materials != null) {
            materials.close();
            materials = null;
        }
    }

    /// Material indices were reassigned after a resource reload: upload the whole table again.
    public static void invalidateMaterials() {
        if (materials != null) {
            materials.uploaded = 0;
        }
    }

    public static @Nullable LodWorld world() {
        return world;
    }

    /// Everything is meshed again, e.g. after a resource reload changed block colours.
    public static void invalidateMeshes() {
        for (Node node : NODES.values()) {
            node.stale = true;
        }
    }

    // ---------------------------------------------------------------- per frame

    public static void onFog(ViewportEvent.RenderFog event) {
        if (!isActive()) {
            return;
        }
        // push the render-distance fog out to the edge of the LODs; environmental fog (water, blindness) stays
        float end = UnoConfig.LOD_DISTANCE.get();
        float span = Math.clamp(end / 10.0F, 32.0F, 512.0F);
        FogData fog = event.getFogData();
        fog.renderDistanceStart = end - span;
        fog.renderDistanceEnd = end;
        // 26.x atmospheric fog thickens linearly up to ~1024 blocks, which would wash LODs out and hide everything
        // past it; stretch it over the LOD distance, keeping its shape (and rain making it denser)
        if (event.getEnvironment() instanceof AtmosphericFogEnvironment && fog.environmentalEnd > 0 && fog.environmentalEnd < end) {
            float scale = end / fog.environmentalEnd;
            fog.environmentalStart *= scale;
            fog.environmentalEnd = end;
        }
    }

    public static void onPrepare(PrepareRenderBuffersEvent event) {
        drawCount = 0;
        waterCount = 0;
        modelDraws = 0;
        LodWorld lodWorld = world;
        QuadArena quadArena = arena;
        FrameBuffers buffers = frame;
        if (lodWorld == null || quadArena == null || buffers == null || !UnoConfig.ENABLED.get()) {
            return;
        }
        frameIndex++;
        drainChanges(lodWorld);
        uploadMeshes(lodWorld);
        // after the meshes: they may reference materials their meshers just added
        if (materials != null) {
            materials.sync();
        }

        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        currentZoom = zoom(camera);
        select(lodWorld, camera.pos, camera.cullFrustum, currentZoom);
        if (frameIndex % 120 == 0) {
            forgetUnused();
        }
        if (DRAWS.isEmpty()) {
            return;
        }

        buffers.rotate();
        drawCount = Math.min(DRAWS.size(), MAX_DRAWS);
        maxQuadsPerDraw = 0;
        // commands: opaque ones at [0, drawCount), fluid ones packed from MAX_DRAWS on, models from 2 * MAX_DRAWS on
        try (GpuBufferSlice.MappedView instances = buffers.instances.currentBuffer().slice(0, (long) drawCount * INSTANCE_BYTES).map(false, true);
             GpuBufferSlice.MappedView commands = buffers.commands.currentBuffer().slice(0, 3L * MAX_DRAWS * COMMAND_BYTES).map(false, true)) {
            ByteBuffer instanceData = instances.data().order(ByteOrder.nativeOrder());
            ByteBuffer commandData = commands.data().order(ByteOrder.nativeOrder());
            for (int i = 0; i < drawCount; i++) {
                long key = DRAWS.getLong(i);
                Node node = NODES.get(key);
                int opaque = node.quadCount - node.fluidCount;
                instanceData.putInt(LodKey.originX(key)).putInt(LodKey.originY(key)).putInt(LodKey.originZ(key)).putInt(LodKey.level(key));
                commandData.position(i * COMMAND_BYTES);
                commandData.putInt(Math.max(0, opaque) * 6).putInt(1).putInt(0).putInt(Math.max(0, node.quadStart) * 4).putInt(i);
                if (node.fluidCount > 0) {
                    commandData.position((MAX_DRAWS + waterCount) * COMMAND_BYTES);
                    commandData.putInt(node.fluidCount * 6).putInt(1).putInt(0).putInt((node.quadStart + opaque) * 4).putInt(i);
                    waterCount++;
                }
                if (node.modelCount > 0) {
                    commandData.position((2 * MAX_DRAWS + modelDraws) * COMMAND_BYTES);
                    commandData.putInt(node.modelCount * 6).putInt(1).putInt(0).putInt(node.modelStart * 4).putInt(i);
                    modelDraws++;
                }
                maxQuadsPerDraw = Math.max(maxQuadsPerDraw, Math.max(Math.max(opaque, node.fluidCount), node.modelCount));
            }
        }
        // with Improved Transparency (OIT) the classic translucent stage does not exist; draw water right after
        // the opaque LODs instead, still blended
        waterInOpaquePass = Minecraft.getInstance().gameRenderer.useImprovedTransparency();
        if (frameIndex % 600 == 0) {
            Uno.LOGGER.debug("Uno: {} nodes, {} pending, {} draws ({} water, {} models), {} / {} quads, {} model quads on GPU, zoom {}",
                NODES.size(), pendingCount, drawCount, waterCount, modelDraws, quadArena.used(), quadArena.capacity,
                modelArena == null ? 0 : modelArena.used(), String.format("%.2f", currentZoom));
        }

        try (GpuBufferSlice.MappedView ubo = buffers.frameUbo.currentBuffer().slice(0, FRAME_UBO_BYTES).map(false, true)) {
            ByteBuffer data = ubo.data().order(ByteOrder.nativeOrder());
            camera.viewRotationMatrix.get(data);
            data.position(64);
            data.putFloat(holeRadius()).putFloat((float) (UnoConfig.TEXTURE_DISTANCE.get() * currentZoom)).putFloat(0).putFloat(0);
        }
        RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(maxQuadsPerDraw * 6);
    }

    public static void onAfterOpaque(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        draw(event.getRenderPass(), PIPELINE, 0, drawCount);
        drawModels(event.getRenderPass());
        if (waterInOpaquePass) {
            draw(event.getRenderPass(), WATER_PIPELINE, MAX_DRAWS, waterCount);
        }
    }

    public static void onAfterTranslucent(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (!waterInOpaquePass) {
            draw(event.getRenderPass(), WATER_PIPELINE, MAX_DRAWS, waterCount);
        }
    }

    private static void draw(RenderPass pass, RenderPipeline pipeline, int firstCommand, int commandCount) {
        QuadArena quadArena = arena;
        FrameBuffers buffers = frame;
        MaterialTable materialTable = materials;
        if (commandCount == 0 || drawCount == 0 || quadArena == null || buffers == null || materialTable == null) {
            return;
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        if (!indices.hasStorage(maxQuadsPerDraw * 6)) {
            return;
        }
        pass.pushDebugGroup(() -> "Uno LOD");
        pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        // nearest + mipmaps: crisp block pixels up close, averaged colour far away
        pass.setUniform(
            "Sampler0",
            Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true)
        );
        pass.setUniform("UnoMaterials", materialTable.buffer);
        pass.setUniform("UnoFrame", buffers.frameUbo.currentBuffer().slice(0, FRAME_UBO_BYTES));
        pass.setUniform("UnoQuads", quadArena.buffer);
        pass.setVertexBuffer(0, buffers.instances.currentBuffer().slice(0, (long) drawCount * INSTANCE_BYTES));
        pass.setIndexBuffer(indices.getBuffer(), indices.type());

        int maxPerCall = Math.max(1, RenderSystem.getDevice().getDeviceInfo().limits().maxDrawIndirectDrawCount());
        GpuBuffer commandBuffer = buffers.commands.currentBuffer();
        for (int done = 0; done < commandCount; done += maxPerCall) {
            int count = Math.min(maxPerCall, commandCount - done);
            pass.drawIndexedIndirect(commandBuffer.slice((long) (firstCommand + done) * COMMAND_BYTES, (long) count * COMMAND_BYTES), count);
        }
        pass.popDebugGroup();
    }

    private static void drawModels(RenderPass pass) {
        QuadArena models = modelArena;
        FrameBuffers buffers = frame;
        if (modelDraws == 0 || models == null || buffers == null) {
            return;
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        if (!indices.hasStorage(maxQuadsPerDraw * 6)) {
            return;
        }
        pass.pushDebugGroup(() -> "Uno LOD models");
        pass.setPipeline(RenderSystem.getCompiledPipeline(MODEL_PIPELINE));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("Sampler2", Minecraft.getInstance().gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        pass.setUniform(
            "Sampler0",
            Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView(),
            RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST, true)
        );
        pass.setUniform("UnoFrame", buffers.frameUbo.currentBuffer().slice(0, FRAME_UBO_BYTES));
        pass.setVertexBuffer(0, models.buffer.slice(0, models.buffer.size()));
        pass.setVertexBuffer(1, buffers.instances.currentBuffer().slice(0, (long) drawCount * INSTANCE_BYTES));
        pass.setIndexBuffer(indices.getBuffer(), indices.type());
        int maxPerCall = Math.max(1, RenderSystem.getDevice().getDeviceInfo().limits().maxDrawIndirectDrawCount());
        GpuBuffer commandBuffer = buffers.commands.currentBuffer();
        for (int done = 0; done < modelDraws; done += maxPerCall) {
            int count = Math.min(maxPerCall, modelDraws - done);
            pass.drawIndexedIndirect(commandBuffer.slice((long) (2 * MAX_DRAWS + done) * COMMAND_BYTES, (long) count * COMMAND_BYTES), count);
        }
        pass.popDebugGroup();
    }

    // ---------------------------------------------------------------- chunk fade-in

    private static final it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap COVERED = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
    private static long coveredFrame = -1;

    /// Whether Uno has drawn LOD geometry over this chunk column. Vanilla and Sodium fade freshly built chunks in
    /// from the fog colour; where a LOD already stood that reads as a white flash, so such chunks skip the fade.
    public static boolean coversChunk(int chunkX, int chunkZ) {
        if (world == null || NODES.isEmpty()) {
            return false;
        }
        if (coveredFrame != frameIndex) {
            COVERED.clear();
            coveredFrame = frameIndex;
        }
        long column = net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
        if (COVERED.containsKey(column)) {
            return COVERED.get(column);
        }
        int bx = chunkX * 16 + 8;
        int bz = chunkZ * 16 + 8;
        boolean covered = false;
        for (int level = 0; level <= LodKey.MAX_LEVEL && !covered; level++) {
            int span = LodKey.span(level);
            int sx = Math.floorDiv(bx, span);
            int sz = Math.floorDiv(bz, span);
            for (int sy = Math.floorDiv(minY, span); sy <= Math.floorDiv(maxY - 1, span) && !covered; sy++) {
                Node node = NODES.get(LodKey.pack(level, sx, sy, sz));
                covered = node != null && node.hasGeometry();
            }
        }
        COVERED.put(column, covered);
        return covered;
    }

    // ---------------------------------------------------------------- bookkeeping

    private static void drainChanges(LodWorld lodWorld) {
        Long key;
        while ((key = lodWorld.changedSections.poll()) != null) {
            Node node = NODES.get(key.longValue());
            if (node != null) {
                node.stale = true;
            }
        }
    }

    private static void uploadMeshes(LodWorld lodWorld) {
        QuadArena quadArena = arena;
        QuadArena models = modelArena;
        if (quadArena == null || models == null) {
            return;
        }
        LodWorld.MeshResult result;
        while ((result = lodWorld.meshResults.poll()) != null) {
            BACKLOG.add(result);
        }
        long uploaded = 0;
        while (!BACKLOG.isEmpty() && uploaded < UPLOAD_BUDGET) {
            result = BACKLOG.poll();
            Node node = NODES.computeIfAbsent(result.key(), k -> new Node());
            if (node.pending) {
                node.pending = false;
                pendingCount--;
            }
            if (result.version() < node.version) {
                result.free();
                continue;
            }
            node.release();
            node.known = true;
            node.version = result.version();
            int quads = result.quadCount();
            if (quads > 0) {
                int start = allocate(quadArena, quads);
                if (start >= 0) {
                    quadArena.upload(start, result.quads());
                    node.quadStart = start;
                    node.quadCount = quads;
                    node.fluidCount = result.fluidCount();
                    uploaded += (long) quads * Mesher.QUAD_BYTES;
                }
            }
            if (result.modelCount() > 0) {
                int start = allocate(models, result.modelCount());
                if (start >= 0) {
                    models.upload(start, result.models());
                    node.modelStart = start;
                    node.modelCount = result.modelCount();
                    uploaded += (long) result.modelCount() * Mesher.MODEL_QUAD_BYTES;
                }
            }
            result.free();
        }
    }

    private static int allocate(QuadArena target, int quads) {
        int start = target.allocate(quads);
        if (start >= 0) {
            return start;
        }
        // evict geometry that has not been drawn recently, oldest first
        LongArrayList victims = new LongArrayList();
        for (Long2ObjectMap.Entry<Node> entry : NODES.long2ObjectEntrySet()) {
            Node node = entry.getValue();
            if (node.hasGeometry() && !node.pending && node.lastSeen < frameIndex - 2) {
                victims.add(entry.getLongKey());
            }
        }
        victims.sort((a, b) -> Long.compare(NODES.get(a).lastSeen, NODES.get(b).lastSeen));
        for (int i = 0; i < victims.size() && start < 0; i++) {
            NODES.remove(victims.getLong(i)).release();
            start = target.allocate(quads);
        }
        if (start < 0) {
            Uno.LOGGER.debug("Uno GPU buffer full ({} units used)", target.used());
        }
        return start;
    }

    private static void forgetUnused() {
        Iterator<Long2ObjectMap.Entry<Node>> iterator = NODES.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            Node node = iterator.next().getValue();
            if (!node.pending && node.lastSeen < frameIndex - 1200) {
                node.release();
                iterator.remove();
            }
        }
    }

    // ---------------------------------------------------------------- selection

    private static int requestsThisFrame;

    /// Octree walk from level 4 down. A node splits into its 8 children when the camera is close enough
    /// and every child is ready; otherwise the node itself is drawn. This covers the area once, with no overlap.
    /// How much narrower the view is than the configured FOV (spyglass, zoom mods). Never below 1, so the wider
    /// FOV while sprinting does not make LODs coarser.
    /// Zooming in makes distant terrain cover more pixels, so finer levels are picked further out.
    private static double zoom(CameraRenderState camera) {
        double base = 1.0 / Math.tan(Math.toRadians(Minecraft.getInstance().options.fov().get()) / 2.0);
        double current = Math.abs(camera.projectionMatrix.m11());
        return current > 0 ? Math.clamp(current / base, 1.0, 16.0) : 1.0;
    }

    private static void select(LodWorld lodWorld, Vec3 camera, Frustum frustum, double zoom) {
        DRAWS.clear();
        requestsThisFrame = 0;
        int distance = UnoConfig.LOD_DISTANCE.get();
        int hole = holeRadius();
        int span = LodKey.span(LodKey.MAX_LEVEL);
        int x0 = Math.floorDiv((int) camera.x - distance, span);
        int x1 = Math.floorDiv((int) camera.x + distance, span);
        int z0 = Math.floorDiv((int) camera.z - distance, span);
        int z1 = Math.floorDiv((int) camera.z + distance, span);
        int y0 = Math.floorDiv(minY, span);
        int y1 = Math.floorDiv(maxY - 1, span);
        double detail = (hole + UnoConfig.DETAIL_DISTANCE.get()) * zoom;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    visit(lodWorld, LodKey.pack(LodKey.MAX_LEVEL, x, y, z), camera, frustum, distance, hole, detail);
                }
            }
        }
    }

    private static void visit(LodWorld lodWorld, long key, Vec3 camera, Frustum frustum, int distance, int hole, double detail) {
        int level = LodKey.level(key);
        int span = LodKey.span(level);
        int ox = LodKey.originX(key);
        int oy = LodKey.originY(key);
        int oz = LodKey.originZ(key);
        if (oy + span <= minY || oy >= maxY) {
            return;
        }
        double near = horizontalDistance(camera, ox, oz, span, false);
        if (near > distance || (level == 0 && insideHole(camera, ox, oy, oz, span, hole))) {
            return;
        }
        if (!frustum.isVisible(new AABB(ox, oy, oz, ox + span, oy + span, oz + span))) {
            return;
        }
        Node node = NODES.get(key);
        if (node != null) {
            node.lastSeen = frameIndex;
        }
        if (level > 0 && near < detail * (1 << (level - 1))) {
            boolean ready = true;
            for (int i = 0; i < 8; i++) {
                if (!isReady(lodWorld, LodKey.child(key, i), camera, distance, hole, near)) {
                    ready = false;
                }
            }
            if (ready) {
                for (int i = 0; i < 8; i++) {
                    visit(lodWorld, LodKey.child(key, i), camera, frustum, distance, hole, detail);
                }
                return;
            }
        }
        draw(lodWorld, key, node, near);
    }

    /// A child is ready when it is known (meshed or empty) or would not be drawn anyway. Unknown children get requested.
    private static boolean isReady(LodWorld lodWorld, long key, Vec3 camera, int distance, int hole, double priority) {
        int level = LodKey.level(key);
        int span = LodKey.span(level);
        int ox = LodKey.originX(key);
        int oy = LodKey.originY(key);
        int oz = LodKey.originZ(key);
        if (oy + span <= minY || oy >= maxY) {
            return true;
        }
        if (horizontalDistance(camera, ox, oz, span, false) > distance) {
            return true;
        }
        if (level == 0 && insideHole(camera, ox, oy, oz, span, hole)) {
            return true;
        }
        Node node = NODES.get(key);
        if (node != null) {
            node.lastSeen = frameIndex;
        }
        if (node != null && node.known) {
            if (node.stale) {
                request(lodWorld, key, node, priority);
            }
            return true;
        }
        request(lodWorld, key, node, priority);
        return false;
    }

    private static void draw(LodWorld lodWorld, long key, @Nullable Node node, double priority) {
        if (node == null || !node.known || node.stale) {
            request(lodWorld, key, node, priority);
            node = NODES.get(key);
        }
        if (node != null && node.hasGeometry()) {
            DRAWS.add(key);
        }
    }

    private static void request(LodWorld lodWorld, long key, @Nullable Node node, double priority) {
        if (node != null && node.pending) {
            return;
        }
        if (requestsThisFrame >= REQUEST_BUDGET || pendingCount >= MAX_PENDING) {
            return;
        }
        if (node == null) {
            node = new Node();
            node.lastSeen = frameIndex;
            NODES.put(key, node);
        }
        node.pending = true;
        node.stale = false;
        pendingCount++;
        requestsThisFrame++;
        lodWorld.requestMesh(key, priority);
    }

    /// Fully inside the part vanilla terrain covers: a cylinder around the camera, as tall as its radius.
    private static boolean insideHole(Vec3 camera, int ox, int oy, int oz, int span, int hole) {
        return horizontalDistance(camera, ox, oz, span, true) < hole && oy > camera.y - hole && oy + span < camera.y + hole;
    }

    /// Horizontal distance from the camera to the nearest (or farthest) point of a column.
    private static double horizontalDistance(Vec3 camera, int ox, int oz, int span, boolean farthest) {
        double dx;
        double dz;
        if (farthest) {
            dx = Math.max(Math.abs(camera.x - ox), Math.abs(camera.x - (ox + span)));
            dz = Math.max(Math.abs(camera.z - oz), Math.abs(camera.z - (oz + span)));
        } else {
            dx = Math.max(0, Math.max(ox - camera.x, camera.x - (ox + span)));
            dz = Math.max(0, Math.max(oz - camera.z, camera.z - (oz + span)));
        }
        return Math.sqrt(dx * dx + dz * dz);
    }

    /// Per-frame GPU buffers written through mapping.
    private static final class FrameBuffers implements AutoCloseable {
        final net.minecraft.client.renderer.MappableRingBuffer instances = new net.minecraft.client.renderer.MappableRingBuffer(
            () -> "Uno section instances", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, MAX_DRAWS * INSTANCE_BYTES
        );
        final net.minecraft.client.renderer.MappableRingBuffer commands = new net.minecraft.client.renderer.MappableRingBuffer(
            () -> "Uno indirect commands", GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_MAP_WRITE, 3 * MAX_DRAWS * COMMAND_BYTES
        );
        final net.minecraft.client.renderer.MappableRingBuffer frameUbo = new net.minecraft.client.renderer.MappableRingBuffer(
            () -> "Uno frame UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FRAME_UBO_BYTES
        );

        void rotate() {
            this.instances.rotate();
            this.commands.rotate();
            this.frameUbo.rotate();
        }

        @Override
        public void close() {
            this.instances.close();
            this.commands.close();
            this.frameUbo.close();
        }
    }
}
