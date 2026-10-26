package net.mistraly.uno.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.mistraly.uno.Uno;

/// 8x8 columns of sections of one level, all heights. Kept in memory as compressed blobs and rewritten whole
/// through a temp file + atomic move, so a crash never leaves a half-written region.
final class Region {
    static final int SHIFT = 3;
    private static final int MAGIC = 0x554E4F31; // "UNO1"

    final Path file;
    private final Long2ObjectOpenHashMap<byte[]> blobs = new Long2ObjectOpenHashMap<>();
    private boolean dirty;

    Region(Path file) {
        this.file = file;
        this.load();
    }

    static long key(int level, int rx, int rz) {
        return LodKey.pack(level, rx, 0, rz);
    }

    static long keyOf(long sectionKey) {
        return key(LodKey.level(sectionKey), LodKey.x(sectionKey) >> SHIFT, LodKey.z(sectionKey) >> SHIFT);
    }

    byte[] get(long sectionKey) {
        return this.blobs.get(sectionKey);
    }

    void put(long sectionKey, byte[] blob) {
        this.blobs.put(sectionKey, blob);
        this.dirty = true;
    }

    void remove(long sectionKey) {
        if (this.blobs.remove(sectionKey) != null) {
            this.dirty = true;
        }
    }

    private void load() {
        if (!Files.exists(this.file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(this.file)))) {
            if (in.readInt() != MAGIC) {
                Uno.LOGGER.warn("Ignoring {}: bad header", this.file);
                return;
            }
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                long key = in.readLong();
                byte[] blob = new byte[in.readInt()];
                in.readFully(blob);
                this.blobs.put(key, blob);
            }
        } catch (IOException e) {
            Uno.LOGGER.error("Failed to read {}", this.file, e);
        }
    }

    void saveIfDirty() {
        if (!this.dirty) {
            return;
        }
        try {
            Files.createDirectories(this.file.getParent());
            Path temp = this.file.resolveSibling(this.file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC);
                out.writeInt(this.blobs.size());
                for (Long2ObjectMap.Entry<byte[]> entry : this.blobs.long2ObjectEntrySet()) {
                    out.writeLong(entry.getLongKey());
                    out.writeInt(entry.getValue().length);
                    out.write(entry.getValue());
                }
            }
            Files.move(temp, this.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            this.dirty = false;
        } catch (IOException e) {
            Uno.LOGGER.error("Failed to write {}", this.file, e);
        }
    }
}
