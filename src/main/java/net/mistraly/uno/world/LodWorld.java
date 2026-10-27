package net.mistraly.uno.world;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.mistraly.uno.Uno;
import net.mistraly.uno.mesh.Mesher;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/// One open dimension: the world thread (ingest, IO, mesh snapshots) plus the mesher pool.
/// The render thread talks to it only through the queues below.
public final class LodWorld implements AutoCloseable {
    /// `fluidCount` quads at the end of `quads` are fluid faces, drawn in the translucent pass.
    /// `models` holds real block-model geometry (level 0 only), `modelCount` quads of it.
    /// `directionCounts` are the opaque quads per face direction, stored in that order before the fluid ones.
    public record MeshResult(
        long key, int version, @Nullable ByteBuffer quads, int[] directionCounts, int fluidCount, @Nullable ByteBuffer models, int modelCount,
        int occluderTop, @Nullable ByteBuffer distant, int distantOpaque, int distantWater
    ) {
        static final int[] NO_DIRECTIONS = new int[6];

        public int quadCount() {
            return this.quads == null ? 0 : this.quads.remaining() / Mesher.QUAD_BYTES;
        }

        public void free() {
            if (this.quads != null) {
                MemoryUtil.memFree(this.quads);
            }
            if (this.models != null) {
                MemoryUtil.memFree(this.models);
            }
            if (this.distant != null) {
                MemoryUtil.memFree(this.distant);
            }
        }
    }

    private record MeshJob(long key, int version, int[] data, int[][] neighbors, double priority, long order)
        implements Comparable<MeshJob> {
        @Override
        public int compareTo(MeshJob other) {
            int byPriority = Double.compare(this.priority, other.priority);
            return byPriority != 0 ? byPriority : Long.compare(this.order, other.order);
        }
    }

    public final Path dir;
    private final IdTables ids;
    private final WorldStore store;
    private final Ingester ingester;
    private final ScheduledExecutorService worldThread;
    private final ExecutorService meshers;
    private final ThreadLocal<Mesher> mesherPerThread;
    private final AtomicLong order = new AtomicLong();
    private volatile boolean closed;
    /// Whether meshes also carry a far terrain mesh for a shader pack (Vitrail); set by the renderer.
    public volatile boolean buildDistant;

    /// Section keys whose voxels changed; drained by the renderer.
    public final Queue<Long> changedSections = new ConcurrentLinkedQueue<>();
    /// Finished meshes; drained by the renderer, which owns (and frees) the buffers.
    public final Queue<MeshResult> meshResults = new ConcurrentLinkedQueue<>();

    public LodWorld(Path dir, Registry<Biome> biomes, int cacheLimit, int meshThreads) {
        this.dir = dir;
        StorageVersion.prepare(dir);
        this.ids = new IdTables(dir, biomes);
        this.store = new WorldStore(dir, cacheLimit);
        this.ingester = new Ingester(this.store, this.ids, this.changedSections::add);
        this.mesherPerThread = ThreadLocal.withInitial(() -> new Mesher(this.ids));
        this.worldThread = Executors.newSingleThreadScheduledExecutor(runnable -> daemon(runnable, "Uno World"));
        this.meshers = new ThreadPoolExecutor(
            meshThreads, meshThreads, 30, TimeUnit.SECONDS, new PriorityBlockingQueue<>(), runnable -> daemon(runnable, "Uno Mesher")
        );
        this.worldThread.scheduleWithFixedDelay(this::flush, 10, 10, TimeUnit.SECONDS);
    }

    private static Thread daemon(Runnable runnable, String name) {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    }

    public void ingest(ChunkSnapshot chunk) {
        this.submit(() -> this.ingester.ingest(chunk));
    }

    /// Asks for a mesh of `key`; the answer (possibly empty) arrives in {@link #meshResults}.
    /// Lower priority values are meshed first.
    public void requestMesh(long key, double priority) {
        this.submit(() -> {
            if (this.closed) {
                return;
            }
            LodSection section = this.store.get(key);
            if (section == null || section.isEmpty()) {
                this.meshResults.add(new MeshResult(key, section == null ? 0 : section.version, null, MeshResult.NO_DIRECTIONS, 0, null, 0, -1, null, 0, 0));
                return;
            }
            int[][] neighbors = new int[6][];
            for (int d = 0; d < 6; d++) {
                LodSection neighbor = this.store.get(neighborKey(key, d));
                neighbors[d] = neighbor == null ? null : neighbor.plane(d);
            }
            MeshJob job = new MeshJob(key, section.version, section.toArray(), neighbors, priority, this.order.getAndIncrement());
            this.meshers.execute(new PrioritizedTask(job, () -> this.runMesh(job)));
        });
    }

    private static long neighborKey(long key, int direction) {
        return switch (direction) {
            case 0 -> LodKey.neighbor(key, 0, -1, 0);
            case 1 -> LodKey.neighbor(key, 0, 1, 0);
            case 2 -> LodKey.neighbor(key, 0, 0, -1);
            case 3 -> LodKey.neighbor(key, 0, 0, 1);
            case 4 -> LodKey.neighbor(key, -1, 0, 0);
            default -> LodKey.neighbor(key, 1, 0, 0);
        };
    }

    private void runMesh(MeshJob job) {
        if (this.closed) {
            return;
        }
        try {
            Mesher mesher = this.mesherPerThread.get();
            ByteBuffer quads = mesher.mesh(job.data, job.neighbors, LodKey.level(job.key), this.buildDistant);
            MeshResult result = new MeshResult(
                job.key, job.version, quads, mesher.directionCounts(), quads == null ? 0 : mesher.fluidCount(), mesher.takeModels(), mesher.modelCount(),
                mesher.occluderTop(), mesher.takeDistant(), mesher.distantOpaque(), mesher.distantWater()
            );
            if (this.closed) {
                result.free();
                return;
            }
            this.meshResults.add(result);
        } catch (RuntimeException e) {
            Uno.LOGGER.error("Meshing {} failed", LodKey.toString(job.key), e);
            this.meshResults.add(new MeshResult(job.key, job.version, null, MeshResult.NO_DIRECTIONS, 0, null, 0, -1, null, 0, 0));
        }
    }

    /// Mesher queue entry ordered by its job.
    private record PrioritizedTask(MeshJob job, Runnable task) implements Runnable, Comparable<PrioritizedTask> {
        @Override
        public void run() {
            this.task.run();
        }

        @Override
        public int compareTo(PrioritizedTask other) {
            return this.job.compareTo(other.job);
        }
    }

    private void submit(Runnable task) {
        if (this.closed) {
            return;
        }
        this.worldThread.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                Uno.LOGGER.error("Uno world task failed", e);
            }
        });
    }

    private void flush() {
        try {
            this.store.flush();
            this.ids.save();
        } catch (RuntimeException e) {
            Uno.LOGGER.error("Uno flush failed", e);
        }
    }

    @Override
    public void close() {
        this.closed = true;
        this.meshers.shutdownNow();
        this.worldThread.execute(this::flush);
        this.worldThread.shutdown();
        try {
            if (!this.worldThread.awaitTermination(10, TimeUnit.SECONDS)) {
                Uno.LOGGER.warn("Uno world thread did not finish saving in time");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        MeshResult result;
        while ((result = this.meshResults.poll()) != null) {
            result.free();
        }
    }
}
