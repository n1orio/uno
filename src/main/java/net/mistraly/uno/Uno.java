package net.mistraly.uno;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.LevelResource;
import net.mistraly.uno.mesh.BlockMaterials;
import net.mistraly.uno.mesh.BlockModels;
import net.mistraly.uno.render.LodRenderer;
import net.mistraly.uno.world.ChunkSnapshot;
import net.mistraly.uno.world.LodWorld;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.resources.VanillaClientListeners;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.slf4j.Logger;

@Mod(value = Uno.MOD_ID, dist = Dist.CLIENT)
public final class Uno {
    public static final String MOD_ID = "uno";
    public static final Logger LOGGER = LogUtils.getLogger();

    /// Light data reaches the client after the chunk itself, so freshly loaded chunks are captured a bit later.
    private static final int LOAD_DELAY_TICKS = 20;
    private static final int SNAPSHOTS_PER_TICK = 48;

    /// chunk pos -> tick at which to capture it
    private static final Long2LongLinkedOpenHashMap PENDING_LOADS = new Long2LongLinkedOpenHashMap();
    private static long tick;
    /// Last chunk the player stood in, for capturing everything that is loaded when the level closes.
    private static int lastChunkX;
    private static int lastChunkZ;

    public Uno(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.CLIENT, UnoConfig.SPEC);
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modBus.addListener(LodRenderer::registerPipelines);
        if (ModList.get().isLoaded("vitrail")) {
            net.mistraly.uno.compat.VitrailBridge.register(
                () -> UnoConfig.ENABLED.get() && LodRenderer.isActive(),
                () -> UnoConfig.LOD_DISTANCE.get(),
                () -> LodRenderer.farPlane());
        }
        modBus.addListener(Uno::addReloadListener);

        NeoForge.EVENT_BUS.addListener(Uno::onLevelLoad);
        NeoForge.EVENT_BUS.addListener(Uno::onLevelUnload);
        NeoForge.EVENT_BUS.addListener(Uno::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(Uno::onChunkUnload);
        NeoForge.EVENT_BUS.addListener(Uno::onClientTick);
        NeoForge.EVENT_BUS.addListener(LodRenderer::onFog);
        NeoForge.EVENT_BUS.addListener(LodRenderer::onPrepare);
        NeoForge.EVENT_BUS.addListener(LodRenderer::onAfterOpaque);
        NeoForge.EVENT_BUS.addListener(LodRenderer::onAfterTranslucent);
    }

    private static void addReloadListener(AddClientReloadListenersEvent event) {
        Identifier id = Identifier.fromNamespaceAndPath(MOD_ID, "block_colors");
        event.addListener(id, (ResourceManagerReloadListener) resourceManager -> {
            BlockMaterials.invalidate();
            BlockModels.invalidate();
            LodRenderer.invalidateMaterials();
            LodRenderer.invalidateMeshes();
        });
        event.addDependency(VanillaClientListeners.MODELS, id);
    }

    private static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ClientLevel level)) {
            return;
        }
        Path dir = Minecraft.getInstance().gameDirectory.toPath()
            .resolve(MOD_ID)
            .resolve(worldName())
            .resolve(sanitize(level.dimension().identifier().toString()));
        LOGGER.info("Uno: opening LOD storage {}", dir);
        PENDING_LOADS.clear();
        LodWorld world = new LodWorld(dir, level.registryAccess().lookupOrThrow(Registries.BIOME), UnoConfig.SECTION_CACHE.get(), UnoConfig.meshThreads());
        world.buildDistant = ModList.get().isLoaded("vitrail");
        LodRenderer.attach(world, level.getMinY(), level.getMaxY() + 1);
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level) {
            captureLoadedChunks(level);
            PENDING_LOADS.clear();
            LodRenderer.detach();
        }
    }

    /// Leaving a world does not unload chunks one by one, so changes near the player would be lost without this.
    private static void captureLoadedChunks(ClientLevel level) {
        LodWorld world = LodRenderer.world();
        if (world == null) {
            return;
        }
        int radius = Minecraft.getInstance().options.getEffectiveRenderDistance() + 3;
        for (int x = lastChunkX - radius; x <= lastChunkX + radius; x++) {
            for (int z = lastChunkZ - radius; z <= lastChunkZ + radius; z++) {
                LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk != null) {
                    world.ingest(ChunkSnapshot.of(chunk));
                }
            }
        }
    }

    /// Re-capture a chunk a moment after a block in it changed (several changes in a row are merged).
    public static void onBlockChanged(BlockPos pos) {
        if (LodRenderer.world() != null) {
            PENDING_LOADS.putAndMoveToLast(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4), tick + LOAD_DELAY_TICKS);
        }
    }

    private static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel().isClientSide() && LodRenderer.world() != null) {
            PENDING_LOADS.putAndMoveToLast(event.getChunk().getPos().pack(), tick + LOAD_DELAY_TICKS);
        }
    }

    private static void onChunkUnload(ChunkEvent.Unload event) {
        LodWorld world = LodRenderer.world();
        if (world == null || !event.getLevel().isClientSide() || !(event.getChunk() instanceof LevelChunk chunk)) {
            return;
        }
        PENDING_LOADS.remove(chunk.getPos().pack());
        world.ingest(ChunkSnapshot.of(chunk));
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        tick++;
        LodWorld world = LodRenderer.world();
        ClientLevel level = Minecraft.getInstance().level;
        if (world == null || level == null) {
            return;
        }
        if (Minecraft.getInstance().player != null) {
            lastChunkX = Minecraft.getInstance().player.chunkPosition().x();
            lastChunkZ = Minecraft.getInstance().player.chunkPosition().z();
        }
        int budget = SNAPSHOTS_PER_TICK;
        while (budget > 0 && !PENDING_LOADS.isEmpty() && PENDING_LOADS.get(PENDING_LOADS.firstLongKey()) <= tick) {
            long pos = PENDING_LOADS.firstLongKey();
            PENDING_LOADS.removeFirstLong();
            LevelChunk chunk = level.getChunkSource().getChunk(ChunkPos.getX(pos), ChunkPos.getZ(pos), ChunkStatus.FULL, false);
            if (chunk != null) {
                world.ingest(ChunkSnapshot.of(chunk));
                budget--;
            }
        }
    }

    private static String worldName() {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            return "local_" + sanitize(String.valueOf(root.getFileName()));
        }
        ServerData data = minecraft.getCurrentServer();
        return data != null ? "server_" + sanitize(data.ip) : "unknown";
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
