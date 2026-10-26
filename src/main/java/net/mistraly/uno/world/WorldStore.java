package net.mistraly.uno.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/// Voxel sections of one dimension: an LRU cache in front of region files. World thread only.
public final class WorldStore {
    private final Path dir;
    private final int cacheLimit;
    private final Long2ObjectOpenHashMap<Region> regions = new Long2ObjectOpenHashMap<>();
    private final LinkedHashMap<Long, LodSection> cache = new LinkedHashMap<>(256, 0.75F, true);

    public WorldStore(Path dir, int cacheLimit) {
        this.dir = dir;
        this.cacheLimit = cacheLimit;
    }

    private Region region(long sectionKey) {
        long regionKey = Region.keyOf(sectionKey);
        Region region = this.regions.get(regionKey);
        if (region == null) {
            int level = LodKey.level(sectionKey);
            Path file = this.dir.resolve("L" + level).resolve("r." + LodKey.x(regionKey) + "." + LodKey.z(regionKey) + ".uno");
            region = new Region(file);
            this.regions.put(regionKey, region);
        }
        return region;
    }

    public @Nullable LodSection get(long key) {
        LodSection section = this.cache.get(key);
        if (section != null) {
            return section;
        }
        byte[] blob = this.region(key).get(key);
        if (blob == null) {
            return null;
        }
        section = new LodSection(key, SectionCodec.decode(blob));
        this.insert(section);
        return section;
    }

    public LodSection getOrCreate(long key) {
        LodSection section = this.get(key);
        if (section == null) {
            section = new LodSection(key);
            this.insert(section);
        }
        return section;
    }

    private void insert(LodSection section) {
        this.cache.put(section.key, section);
        if (this.cache.size() > this.cacheLimit) {
            Iterator<Map.Entry<Long, LodSection>> iterator = this.cache.entrySet().iterator();
            while (this.cache.size() > this.cacheLimit && iterator.hasNext()) {
                LodSection oldest = iterator.next().getValue();
                if (oldest == section) {
                    continue;
                }
                this.writeBack(oldest);
                iterator.remove();
            }
        }
    }

    /// Call after changing a section: if it was evicted while being edited, its changes go straight to the region.
    public void commit(LodSection section) {
        if (this.cache.get(section.key) != section) {
            this.writeBack(section);
        }
    }

    private void writeBack(LodSection section) {
        if (!section.dirty) {
            return;
        }
        Region region = this.region(section.key);
        if (section.isEmpty()) {
            region.remove(section.key);
        } else {
            region.put(section.key, SectionCodec.encode(section.data));
        }
        section.dirty = false;
    }

    public void flush() {
        for (LodSection section : this.cache.values()) {
            this.writeBack(section);
        }
        for (Region region : this.regions.values()) {
            region.saveIfDirty();
        }
    }
}
