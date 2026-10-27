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
import net.minecraft.util.Mth;
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
    /// Command buffer layout: opaque runs (up to 3 per section), then fluid, then model commands.
    private static final int WATER_COMMANDS = 3 * MAX_DRAWS;
    private static final int MODEL_COMMANDS = 4 * MAX_DRAWS;
    private static final int COMMAND_CAPACITY = 5 * MAX_DRAWS;
    private static final int FRAME_UBO_BYTES = 112;
    private static final long UPLOAD_BUDGET = 32L * 1024 * 1024;
    private static final int REQUEST_BUDGET = 1024;
    private static final int MAX_PENDING = 8192;

    public static final VertexFormat SECTION_FORMAT = VertexFormat.builder(1).addAttribute("SectionInfo", GpuFormat.RGBA32_SINT).build();
    private static final BindGroupLayout UNO_LAYOUT = BindGroupLayout.builder()
        .withUniform("UnoFrame", UniformType.UNIFORM_BUFFER)
        .withUniform("UnoQuads", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
        .withUniform("UnoMaterials", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_FLOAT)
        .withUniform("UnoMask", UniformType.TEXEL_BUFFER, GpuFormat.R8_UINT)
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
        .withUniform("UnoMask", UniformType.TEXEL_BUFFER, GpuFormat.R8_UINT)
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
        int[] directionCounts = new int[6];
        int fluidCount;
        /// Far-terrain meshes for a Vitrail shader pack (Distant Horizons' vertex layout), one buffer per half.
        @Nullable GpuBuffer distantOpaque;
        @Nullable GpuBuffer distantWater;
        int distantOpaqueQuads;
        int distantWaterQuads;
        int modelStart = -1;
        int modelCount;
        /// World y below which the section's footprint is opaque terrain, or MIN_VALUE: an occluder for culling.
        int occluderY = Integer.MIN_VALUE;
        long lastSeen;

        boolean hasGeometry() {
            return this.quadStart >= 0 || this.modelStart >= 0;
        }

        void release() {
            if (this.quadStart >= 0 && arena != null) {
                arena.release(this.quadStart, this.quadCount);
            }
            if (this.distantOpaque != null) {
                this.distantOpaque.close();
                this.distantOpaque = null;
            }
            if (this.distantWater != null) {
                this.distantWater.close();
                this.distantWater = null;
            }
            this.distantOpaqueQuads = 0;
            this.distantWaterQuads = 0;
            if (this.modelStart >= 0 && modelArena != null) {
                modelArena.release(this.modelStart, this.modelCount);
            }
            this.quadStart = -1;
            this.quadCount = 0;
            this.directionCounts = new int[6];
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
    private static @Nullable SectionMask mask;
    private static final Long2ObjectOpenHashMap<Node> NODES = new Long2ObjectOpenHashMap<>();
    private static final ArrayDeque<LodWorld.MeshResult> BACKLOG = new ArrayDeque<>();
    private static final LongArrayList DRAWS = new LongArrayList();
    private static long frameIndex;
    private static int pendingCount;
    private static int drawCount;
    private static int opaqueCommands;
    private static int waterCount;
    private static int modelDraws;
    private static boolean waterInOpaquePass;
    /// Vitrail (shader packs on Vulkan) is installed and has the far terrain door Uno uses. Checked once.
    public static final boolean VITRAIL = net.neoforged.fml.ModList.get().isLoaded("vitrail") && vitrailDoor();

    private static boolean vitrailDoor() {
        try {
            Class.forName("dev.vitrail.render.DistantDraw", false, LodRenderer.class.getClassLoader());
            Class.forName("dev.vitrail.dh.DhLods$Section", false, LodRenderer.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
    /// Per-section data for Vitrail, rebuilt with the draw list: corner, buffers and quad counts per half.
    private static GpuBuffer[] vitrailOpaqueBuffers = new GpuBuffer[0];
    private static GpuBuffer[] vitrailWaterBuffers = new GpuBuffer[0];
    private static boolean handedToVitrail;
    /// With a pack drawing, sections under vanilla terrain are still selected, for the pack: it dissolves the edge of
    /// the vanilla chunks into the far terrain it expects behind them (Distant Horizons draws there). Without them the
    /// dissolved edge was a hole. Uno's own textured pass keeps cutting them away in its shader.
    private static boolean packCut() {
        return VITRAIL && net.mistraly.uno.compat.VitrailBridge.packActive();
    }

    /// This frame's sections are split: near ones drawn by Uno, far ones by the pack.
    private static boolean splitForPack;
    private static int[] vitrailOpaque = new int[0];
    private static int[] vitrailWater = new int[0];
    private static int[] vitrailCornerX = new int[0];
    private static int[] vitrailCornerY = new int[0];
    private static int[] vitrailCornerZ = new int[0];
    /// 32-bit quad indices (0,1,2, 2,3,0 per quad) for Vitrail, which binds INT indices; the game's own sequential
    /// buffer switches to 16-bit when small and was read as garbage triangles.
    private static @Nullable GpuBuffer vitrailIndices;
    private static int vitrailIndexQuads;
    private static int vitrailMaxQuads;
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
        mask = new SectionMask(worldMinY, worldMaxY);
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
        if (vitrailIndices != null) {
            vitrailIndices.close();
            vitrailIndices = null;
            vitrailIndexQuads = 0;
        }
        if (frame != null) {
            frame.close();
            frame = null;
        }
        if (materials != null) {
            materials.close();
            materials = null;
        }
        if (mask != null) {
            mask.close();
            mask = null;
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

    private static long prepareNanos;

    public static void onPrepare(PrepareRenderBuffersEvent event) {
        long started = System.nanoTime();
        try {
            prepare(event);
        } finally {
            prepareNanos += System.nanoTime() - started;
        }
    }

    private static @Nullable Vec3 lastSelectPosition;
    private static float lastSelectXRot;
    private static float lastSelectYRot;
    private static double lastSelectZoom;
    private static long lastSelectFrame = -1000;

    private static void prepare(PrepareRenderBuffersEvent event) {
        LodWorld lodWorld = world;
        QuadArena quadArena = arena;
        FrameBuffers buffers = frame;
        if (lodWorld == null || quadArena == null || buffers == null || !UnoConfig.ENABLED.get()) {
            drawCount = 0;
            return;
        }
        frameIndex++;
        if (VITRAIL) {
            net.mistraly.uno.compat.VitrailBridge.update(UnoConfig.LOD_DISTANCE.get(), UnoConfig.LOD_DISTANCE.get() + 512.0F);
        }
        int changes = drainChanges(lodWorld);
        int uploads = uploadMeshes(lodWorld);
        // after the meshes: they may reference materials their meshers just added
        if (materials != null) {
            materials.sync();
        }

        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        boolean maskChanged = mask != null && mask.update(camera.pos, holeRadius());
        currentZoom = zoom(camera);
        if (frameIndex % 120 == 0) {
            forgetUnused();
        }
        // The draw list only changes when the camera moved or turned noticeably, or something new arrived;
        // otherwise last frame's instance and command buffers are drawn again and only the camera UBO is written.
        boolean moved = lastSelectPosition == null
            || camera.pos.distanceToSqr(lastSelectPosition) > 0.25
            || Math.abs(camera.xRot - lastSelectXRot) > 0.5F
            || Math.abs(camera.yRot - lastSelectYRot) > 0.5F
            || currentZoom != lastSelectZoom;
        if (moved || changes > 0 || uploads > 0 || maskChanged || frameIndex - lastSelectFrame >= 20) {
            lastSelectPosition = camera.pos;
            lastSelectXRot = camera.xRot;
            lastSelectYRot = camera.yRot;
            lastSelectZoom = currentZoom;
            lastSelectFrame = frameIndex;
            select(lodWorld, camera.pos, camera.cullFrustum, currentZoom);
            writeDrawList(buffers, camera);
        }
        if (drawCount == 0) {
            return;
        }
        // with Improved Transparency (OIT) the classic translucent stage does not exist; draw water right after
        // the opaque LODs instead, still blended
        waterInOpaquePass = Minecraft.getInstance().gameRenderer.useImprovedTransparency();
        if (frameIndex % 600 == 0) {
            Uno.LOGGER.debug("Uno: {} nodes, {} pending, {} draws ({} culled, {} opaque cmds, {} water, {} models), {} / {} quads, {} model quads on GPU, zoom {}, {} ms/frame CPU",
                NODES.size(), pendingCount, drawCount, culledThisFrame, opaqueCommands, waterCount, modelDraws, quadArena.used(), quadArena.capacity,
                modelArena == null ? 0 : modelArena.used(), String.format("%.2f", currentZoom), String.format("%.3f", prepareNanos / 600.0 / 1.0E6));
            prepareNanos = 0;
        }

        buffers.frameUbo.rotate();
        try (GpuBufferSlice.MappedView ubo = buffers.frameUbo.currentBuffer().slice(0, FRAME_UBO_BYTES).map(false, true)) {
            ByteBuffer data = ubo.data().order(ByteOrder.nativeOrder());
            camera.viewRotationMatrix.get(data);
            data.position(64);
            data.putFloat(holeRadius()).putFloat((float) (UnoConfig.TEXTURE_DISTANCE.get() * currentZoom)).putFloat(0).putFloat(0);
            SectionMask sectionMask = mask;
            boolean maskValid = sectionMask != null && sectionMask.valid;
            data.putInt(maskValid ? sectionMask.originX * 16 : 0)
                .putInt(maskValid ? sectionMask.minSection() * 16 : 0)
                .putInt(maskValid ? sectionMask.originZ * 16 : 0)
                .putInt(maskValid ? sectionMask.size : 0);
            data.putInt(maskValid ? sectionMask.sections() : 0).putInt(maskValid ? 1 : 0).putInt(0).putInt(0);
        }
        RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).requestIndexCount(maxQuadsPerDraw * 6);
    }

    /// Writes the instance data and indirect commands of the sections chosen by {@link #select}.
    private static void writeDrawList(FrameBuffers buffers, CameraRenderState camera) {
        drawCount = 0;
        opaqueCommands = 0;
        waterCount = 0;
        modelDraws = 0;
        if (DRAWS.isEmpty()) {
            return;
        }
        buffers.instances.rotate();
        buffers.commands.rotate();
        drawCount = Math.min(DRAWS.size(), MAX_DRAWS);
        vitrailMaxQuads = 0;
        if (VITRAIL && vitrailOpaque.length < drawCount) {
            int size = Math.max(drawCount, vitrailOpaque.length * 2);
            vitrailOpaqueBuffers = java.util.Arrays.copyOf(vitrailOpaqueBuffers, size);
            vitrailWaterBuffers = java.util.Arrays.copyOf(vitrailWaterBuffers, size);
            vitrailOpaque = java.util.Arrays.copyOf(vitrailOpaque, size);
            vitrailWater = java.util.Arrays.copyOf(vitrailWater, size);
            vitrailCornerX = java.util.Arrays.copyOf(vitrailCornerX, size);
            vitrailCornerY = java.util.Arrays.copyOf(vitrailCornerY, size);
            vitrailCornerZ = java.util.Arrays.copyOf(vitrailCornerZ, size);
        }
        maxQuadsPerDraw = 0;
        // With a pack, sections split by distance: near ones stay Uno's (textured), far ones go to the pack.
        splitForPack = VITRAIL && net.mistraly.uno.compat.VitrailBridge.packActive();
        double packFrom = UnoConfig.SHADER_TEXTURE_DISTANCE.get() * currentZoom;
        // commands: opaque runs from 0 (up to 3 per section), fluid from WATER_COMMANDS, models from MODEL_COMMANDS
        try (GpuBufferSlice.MappedView instances = buffers.instances.currentBuffer().slice(0, (long) drawCount * INSTANCE_BYTES).map(false, true);
             GpuBufferSlice.MappedView commands = buffers.commands.currentBuffer().slice(0, (long) COMMAND_CAPACITY * COMMAND_BYTES).map(false, true)) {
            ByteBuffer instanceData = instances.data().order(ByteOrder.nativeOrder());
            ByteBuffer commandData = commands.data().order(ByteOrder.nativeOrder());
            for (int i = 0; i < drawCount; i++) {
                long key = DRAWS.getLong(i);
                Node node = NODES.get(key);
                int opaque = node.quadCount - node.fluidCount;
                instanceData.putInt(LodKey.originX(key)).putInt(LodKey.originY(key)).putInt(LodKey.originZ(key)).putInt(LodKey.level(key));
                if (VITRAIL) {
                    recordForVitrail(i, node, key);
                }
                if (splitForPack) {
                    // the pack draws every section, water included; Uno adds textures over the near ones
                    int span = LodKey.span(LodKey.level(key));
                    if (horizontalDistance(camera.pos, LodKey.originX(key), LodKey.originZ(key), span, false) >= packFrom) {
                        continue;
                    }
                }
                if (node.quadStart >= 0) {
                    int visible = visibleDirections(camera.pos, key);
                    int offset = 0;
                    int runStart = -1;
                    int runQuads = 0;
                    // directions are stored in order; adjacent visible ones become one command
                    for (int d = 0; d <= 6; d++) {
                        boolean draw = d < 6 && (visible & (1 << d)) != 0 && node.directionCounts[d] > 0;
                        if (draw) {
                            if (runStart < 0) {
                                runStart = offset;
                            }
                            runQuads += node.directionCounts[d];
                        } else if (runStart >= 0) {
                            commandData.position(opaqueCommands * COMMAND_BYTES);
                            commandData.putInt(runQuads * 6).putInt(1).putInt(0).putInt((node.quadStart + runStart) * 4).putInt(i);
                            opaqueCommands++;
                            maxQuadsPerDraw = Math.max(maxQuadsPerDraw, runQuads);
                            runStart = -1;
                            runQuads = 0;
                        }
                        if (d < 6) {
                            offset += node.directionCounts[d];
                        }
                    }
                }
                if (node.fluidCount > 0 && !splitForPack) {
                    commandData.position((WATER_COMMANDS + waterCount) * COMMAND_BYTES);
                    commandData.putInt(node.fluidCount * 6).putInt(1).putInt(0).putInt((node.quadStart + opaque) * 4).putInt(i);
                    waterCount++;
                }
                if (node.modelCount > 0) {
                    commandData.position((MODEL_COMMANDS + modelDraws) * COMMAND_BYTES);
                    commandData.putInt(node.modelCount * 6).putInt(1).putInt(0).putInt(node.modelStart * 4).putInt(i);
                    modelDraws++;
                }
                maxQuadsPerDraw = Math.max(maxQuadsPerDraw, Math.max(node.fluidCount, node.modelCount));
            }
        }
    }

    /// With Vitrail the opaque half goes to the pack ahead of the world's chunks, where Distant Horizons hands its own
    /// over: the far terrain has a depth image of its own, so whatever is drawn later paints over it. Handed over
    /// after the chunks it painted over them instead, and the near LODs showed through the ground.
    public static void onAfterSky(RenderLevelStageEvent.AfterSky event) {
        handedToVitrail = VITRAIL && handOverToVitrail();
    }

    public static void onAfterOpaque(RenderLevelStageEvent.AfterOpaqueBlocks event) {
        if (handedToVitrail) {
            // the pack draws the far sections (lit and shadowed by it); Uno draws only the near, textured ones
            if (waterInOpaquePass) {
                net.mistraly.uno.compat.VitrailBridge.drawWater();
            }
            if (!splitForPack) {
                return;
            }
        }
        draw(event.getRenderPass(), PIPELINE, 0, opaqueCommands);
        drawModels(event.getRenderPass());
        if (waterInOpaquePass) {
            draw(event.getRenderPass(), WATER_PIPELINE, WATER_COMMANDS, waterCount);
        }
    }

    public static void onAfterTranslucent(RenderLevelStageEvent.AfterTranslucentBlocks event) {
        if (handedToVitrail) {
            if (!waterInOpaquePass) {
                net.mistraly.uno.compat.VitrailBridge.drawWater();
            }
            if (!splitForPack) {
                return;
            }
        }
        if (!waterInOpaquePass) {
            draw(event.getRenderPass(), WATER_PIPELINE, WATER_COMMANDS, waterCount);
        }
    }

    /// Hands this frame's sections to Vitrail's far terrain door. Returns true when the pack drew the opaque half.
    /// Uses the quad/section buffers Uno already has, so nothing is copied for it.
    private static boolean handOverToVitrail() {
        if (drawCount == 0 || vitrailOpaque.length < drawCount) {
            return false;
        }
        GpuBuffer indices = vitrailIndexBuffer(vitrailMaxQuads);
        net.mistraly.uno.compat.VitrailBridge.setSections(drawCount, vitrailCornerX, vitrailCornerY, vitrailCornerZ,
            vitrailOpaqueBuffers, vitrailOpaque, vitrailWaterBuffers, vitrailWater, indices);
        return net.mistraly.uno.compat.VitrailBridge.drawOpaque();
    }

    private static void draw(RenderPass pass, RenderPipeline pipeline, int firstCommand, int commandCount) {
        QuadArena quadArena = arena;
        FrameBuffers buffers = frame;
        MaterialTable materialTable = materials;
        if (commandCount == 0 || drawCount == 0 || quadArena == null || buffers == null || materialTable == null || mask == null) {
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
        pass.setUniform("UnoMask", mask.buffer);
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
        if (modelDraws == 0 || models == null || buffers == null || mask == null) {
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
        pass.setUniform("UnoMask", mask.buffer);
        pass.setVertexBuffer(0, models.buffer.slice(0, models.buffer.size()));
        pass.setVertexBuffer(1, buffers.instances.currentBuffer().slice(0, (long) drawCount * INSTANCE_BYTES));
        pass.setIndexBuffer(indices.getBuffer(), indices.type());
        int maxPerCall = Math.max(1, RenderSystem.getDevice().getDeviceInfo().limits().maxDrawIndirectDrawCount());
        GpuBuffer commandBuffer = buffers.commands.currentBuffer();
        for (int done = 0; done < modelDraws; done += maxPerCall) {
            int count = Math.min(maxPerCall, modelDraws - done);
            pass.drawIndexedIndirect(commandBuffer.slice((long) (MODEL_COMMANDS + done) * COMMAND_BYTES, (long) count * COMMAND_BYTES), count);
        }
        pass.popDebugGroup();
    }

    private static GpuBuffer vitrailIndexBuffer(int quads) {
        if (vitrailIndices == null || vitrailIndexQuads < quads) {
            if (vitrailIndices != null) {
                vitrailIndices.close();
            }
            vitrailIndexQuads = Math.max(Mth.smallestEncompassingPowerOfTwo(Math.max(quads, 1)), 4096);
            ByteBuffer data = MemoryUtil.memAlloc(vitrailIndexQuads * 6 * 4).order(ByteOrder.nativeOrder());
            try {
                for (int q = 0; q < vitrailIndexQuads; q++) {
                    int v = q * 4;
                    data.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
                }
                data.flip();
                vitrailIndices = RenderSystem.getDevice().createBuffer(() -> "Uno far terrain indices", GpuBuffer.USAGE_INDEX, data);
            } finally {
                MemoryUtil.memFree(data);
            }
        }
        return vitrailIndices;
    }

    /// Puts a section's far-terrain mesh (opaque quads, then water quads, 64 bytes a quad) into two GPU buffers.
    private static void uploadDistant(Node node, LodWorld.MeshResult result) {
        ByteBuffer distant = result.distant();
        int opaqueQuads = result.distantOpaque();
        int waterQuads = result.distantWater();
        if (opaqueQuads > 0) {
            ByteBuffer slice = distant.slice(0, opaqueQuads * Mesher.DISTANT_QUAD_BYTES);
            node.distantOpaque = RenderSystem.getDevice().createBuffer(() -> "Uno far terrain", GpuBuffer.USAGE_VERTEX, slice);
            node.distantOpaqueQuads = opaqueQuads;
        }
        if (waterQuads > 0) {
            ByteBuffer slice = distant.slice(opaqueQuads * Mesher.DISTANT_QUAD_BYTES, waterQuads * Mesher.DISTANT_QUAD_BYTES);
            node.distantWater = RenderSystem.getDevice().createBuffer(() -> "Uno far water", GpuBuffer.USAGE_VERTEX, slice);
            node.distantWaterQuads = waterQuads;
        }
    }

    /// Records one section for Vitrail: its corner and where its opaque and water quads start and how many there are.
    private static void recordForVitrail(int i, Node node, long key) {
        vitrailCornerX[i] = LodKey.originX(key);
        vitrailCornerY[i] = LodKey.originY(key);
        vitrailCornerZ[i] = LodKey.originZ(key);
        vitrailOpaqueBuffers[i] = node.distantOpaque;
        vitrailWaterBuffers[i] = node.distantWater;
        vitrailOpaque[i] = node.distantOpaque != null ? node.distantOpaqueQuads : 0;
        vitrailWater[i] = node.distantWater != null ? node.distantWaterQuads : 0;
        vitrailMaxQuads = Math.max(vitrailMaxQuads, Math.max(vitrailOpaque[i], vitrailWater[i]));
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

    private static int drainChanges(LodWorld lodWorld) {
        Long key;
        int count = 0;
        while ((key = lodWorld.changedSections.poll()) != null) {
            count++;
            Node node = NODES.get(key.longValue());
            if (node != null) {
                node.stale = true;
            }
        }
        return count;
    }

    private static int uploadMeshes(LodWorld lodWorld) {
        QuadArena quadArena = arena;
        QuadArena models = modelArena;
        if (quadArena == null || models == null) {
            return 0;
        }
        int processed = 0;
        LodWorld.MeshResult result;
        while ((result = lodWorld.meshResults.poll()) != null) {
            BACKLOG.add(result);
        }
        long uploaded = 0;
        while (!BACKLOG.isEmpty() && uploaded < UPLOAD_BUDGET) {
            result = BACKLOG.poll();
            processed++;
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
            node.occluderY = occluderY(result.key(), result.occluderTop());
            int quads = result.quadCount();
            if (quads > 0) {
                int start = allocate(quadArena, quads);
                if (start >= 0) {
                    quadArena.upload(start, result.quads());
                    if (VITRAIL && result.distant() != null) {
                        uploadDistant(node, result);
                    }
                    node.quadStart = start;
                    node.quadCount = quads;
                    node.directionCounts = result.directionCounts();
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
        return processed;
    }

    /// Occluder height in world y. Coarse levels round terrain up (a voxel is solid if any block in it is),
    /// so their occluder is lowered by one voxel to stay conservative.
    private static int occluderY(long key, int topVoxels) {
        if (topVoxels <= 0) {
            return Integer.MIN_VALUE;
        }
        int level = LodKey.level(key);
        int scale = 1 << level;
        int voxels = level > 0 ? topVoxels - 1 : topVoxels;
        return voxels <= 0 ? Integer.MIN_VALUE : LodKey.originY(key) + voxels * scale;
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
    /// Sections chosen this frame (with geometry or as occluders), before culling.
    private static final LongArrayList CANDIDATES = new LongArrayList();
    private static final int HORIZON_BINS = 1024;
    private static final float[] HORIZON = new float[HORIZON_BINS];
    private static int culledThisFrame;

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
        CANDIDATES.clear();
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
        cullHidden(camera);
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
        if (near > distance) {
            return;
        }
        if (coveredByVanilla(key, camera, hole)) {
            keepWarm(lodWorld, key, near);
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
        if (coveredByVanilla(key, camera, hole)) {
            keepWarm(lodWorld, key, priority);
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

    /// A section vanilla draws right now is not drawn, but its mesh is kept ready (and up to date) so that when
    /// the chunk unloads behind a moving player the LOD shows in the same frame instead of a gap.
    /// Meshed after everything that is actually visible.
    private static void keepWarm(LodWorld lodWorld, long key, double priority) {
        Node node = NODES.get(key);
        if (node != null) {
            node.lastSeen = frameIndex;
        }
        if (node == null || !node.known || node.stale) {
            request(lodWorld, key, node, priority + 1.0E6);
        }
    }

    private static void draw(LodWorld lodWorld, long key, @Nullable Node node, double priority) {
        if (node == null || !node.known || node.stale) {
            request(lodWorld, key, node, priority);
            node = NODES.get(key);
        }
        if (node != null && node.known && (node.hasGeometry() || node.occluderY != Integer.MIN_VALUE)) {
            CANDIDATES.add(key);
        }
    }

    /// Horizon culling. Front to back, a horizon (highest elevation angle of opaque terrain so far) is kept per
    /// azimuth bin; a section whose top lies below the horizon over its whole angular span is hidden behind hills.
    /// Assumes terrain is a heightfield (solid below its surface), which holds as seen from outside.
    private static void cullHidden(Vec3 camera) {
        culledThisFrame = 0;
        if (!UnoConfig.OCCLUSION_CULLING.get()) {
            for (int i = 0; i < CANDIDATES.size(); i++) {
                Node node = NODES.get(CANDIDATES.getLong(i));
                if (node.hasGeometry()) {
                    DRAWS.add(CANDIDATES.getLong(i));
                }
            }
            return;
        }
        int count = CANDIDATES.size();
        long[] keys = CANDIDATES.toLongArray();
        double[] near = new double[count];
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            int span = LodKey.span(LodKey.level(keys[i]));
            near[i] = horizontalDistance(camera, LodKey.originX(keys[i]), LodKey.originZ(keys[i]), span, false);
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(near[a], near[b]));
        java.util.Arrays.fill(HORIZON, Float.NEGATIVE_INFINITY);
        for (int index : order) {
            long key = keys[index];
            Node node = NODES.get(key);
            int span = LodKey.span(LodKey.level(key));
            int ox = LodKey.originX(key);
            int oy = LodKey.originY(key);
            int oz = LodKey.originZ(key);
            boolean inside = camera.x >= ox && camera.x <= ox + span && camera.z >= oz && camera.z <= oz + span;
            if (inside) {
                if (node.hasGeometry()) {
                    DRAWS.add(key);
                }
                continue;
            }
            double far = horizontalDistance(camera, ox, oz, span, true);
            double nearest = Math.max(near[index], 1.0);
            // angular span of the footprint, measured from its centre so it never wraps
            double centre = Math.atan2(oz + span / 2.0 - camera.z, ox + span / 2.0 - camera.x);
            double low = Double.MAX_VALUE;
            double high = -Double.MAX_VALUE;
            for (int corner = 0; corner < 4; corner++) {
                double cx = ox + ((corner & 1) == 0 ? 0 : span) - camera.x;
                double cz = oz + ((corner & 2) == 0 ? 0 : span) - camera.z;
                double offset = Math.IEEEremainder(Math.atan2(cz, cx) - centre, Math.PI * 2);
                low = Math.min(low, offset);
                high = Math.max(high, offset);
            }
            double binsPerRadian = HORIZON_BINS / (Math.PI * 2);
            double start = (centre + low + Math.PI) * binsPerRadian;
            double end = (centre + high + Math.PI) * binsPerRadian;

            double top = oy + span - camera.y;
            float topAngle = (float) Math.atan2(top, top >= 0 ? nearest : far);
            boolean visible = false;
            for (int b = (int) Math.floor(start); b <= (int) Math.floor(end) && !visible; b++) {
                visible = HORIZON[Math.floorMod(b, HORIZON_BINS)] < topAngle;
            }
            if (visible) {
                if (node.hasGeometry()) {
                    DRAWS.add(key);
                }
            } else {
                culledThisFrame++;
            }
            if (node.occluderY != Integer.MIN_VALUE) {
                double height = node.occluderY - camera.y;
                // lowest elevation of the occluder over its footprint; only bins it covers completely are raised
                float occluderAngle = (float) Math.atan2(height, height >= 0 ? far : nearest);
                for (int b = (int) Math.ceil(start); b < (int) Math.floor(end); b++) {
                    int bin = Math.floorMod(b, HORIZON_BINS);
                    HORIZON[bin] = Math.max(HORIZON[bin], occluderAngle);
                }
            }
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

    /// Bit d set when faces of direction d (DOWN, UP, NORTH, SOUTH, WEST, EAST) inside the section can face the
    /// camera: a +X face at plane x is seen only from x' > x, so if the camera is left of the section's left edge
    /// no +X face of it can be seen.
    private static int visibleDirections(Vec3 camera, long key) {
        int span = LodKey.span(LodKey.level(key));
        int ox = LodKey.originX(key);
        int oy = LodKey.originY(key);
        int oz = LodKey.originZ(key);
        int bits = 0;
        if (camera.y < oy + span) bits |= 1;
        if (camera.y > oy) bits |= 2;
        if (camera.z < oz + span) bits |= 4;
        if (camera.z > oz) bits |= 8;
        if (camera.x < ox + span) bits |= 16;
        if (camera.x > ox) bits |= 32;
        return bits;
    }

    /// Whether vanilla / Sodium draw every 16^3 section inside this LOD section (checked for levels 0 and 1).
    /// Before the first section mask exists, falls back to the render-distance cylinder.
    private static boolean coveredByVanilla(long key, Vec3 camera, int hole) {
        int level = LodKey.level(key);
        if (level > 1 || packCut()) {
            return false;
        }
        int span = LodKey.span(level);
        int ox = LodKey.originX(key);
        int oy = LodKey.originY(key);
        int oz = LodKey.originZ(key);
        SectionMask sectionMask = mask;
        if (sectionMask == null || !sectionMask.valid) {
            return level == 0 && insideHole(camera, ox, oy, oz, span, hole);
        }
        int chunks = span / 16;
        for (int sy = 0; sy < chunks; sy++) {
            int sectionY = (oy >> 4) + sy;
            if (sectionY < sectionMask.minSection() || sectionY >= sectionMask.minSection() + sectionMask.sections()) {
                continue;
            }
            for (int dz = 0; dz < chunks; dz++) {
                for (int dx = 0; dx < chunks; dx++) {
                    if (!sectionMask.drawn((ox >> 4) + dx, sectionY, (oz >> 4) + dz)) {
                        return false;
                    }
                }
            }
        }
        return true;
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
            () -> "Uno indirect commands", GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_MAP_WRITE, COMMAND_CAPACITY * COMMAND_BYTES
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
