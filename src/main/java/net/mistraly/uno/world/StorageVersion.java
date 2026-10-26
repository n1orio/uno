package net.mistraly.uno.world;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import net.mistraly.uno.Uno;

/// Bumped whenever stored voxels would come out differently (format or mip rules). Storage of an older version
/// is wiped and rebuilt from chunks as the player plays.
final class StorageVersion {
    /// 2: top layer decides the mip, so shallow water survives
    /// 3: sky light from the heightmap, flowing water dropped
    /// 4: plants are stored (drawn with their models up close)
    /// 5: voxel layout with block light (16-bit state ids), full-cube test by solid render
    /// 6: sky light above the solid surface ignores leaves (no more black forest floors)
    /// 7: deep flowing water is kept (river beds no longer show through)
    private static final int CURRENT = 7;

    private StorageVersion() {
    }

    static void prepare(Path dir) {
        Path file = dir.resolve("uno.version");
        try {
            Files.createDirectories(dir);
            String stored = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8).trim() : "";
            if (stored.equals(String.valueOf(CURRENT))) {
                return;
            }
            if (!stored.isEmpty() || hasData(dir)) {
                Uno.LOGGER.info("Uno: storage {} is version '{}', rebuilding as version {}", dir, stored, CURRENT);
                try (Stream<Path> entries = Files.list(dir)) {
                    for (Path entry : entries.toList()) {
                        deleteRecursively(entry);
                    }
                }
            }
            Files.writeString(file, String.valueOf(CURRENT), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Uno.LOGGER.error("Uno: could not check storage version of {}", dir, e);
        }
    }

    private static boolean hasData(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isPresent();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        try (Stream<Path> walk = Files.walk(path)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
