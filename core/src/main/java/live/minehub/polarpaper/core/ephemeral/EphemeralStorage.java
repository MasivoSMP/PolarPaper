package live.minehub.polarpaper.core.ephemeral;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Properties;
import java.util.UUID;
import java.util.Collection;

/** Durable ownership records only; never contains world content. Does not follow symbolic links. */
public final class EphemeralStorage {
    private static final String MARKER = ".polar-ephemeral";

    private EphemeralStorage() { }

    public static void reserve(Plugin plugin, Path directory, String worldKey) {
        Path folder = directory.toAbsolutePath().normalize();
        Path records = plugin.getDataFolder().toPath().resolve("ephemeral-instances");
        String token = UUID.randomUUID().toString();
        try {
            rejectSymlinkAncestors(folder);
            rejectSymlinkAncestors(records.toAbsolutePath().normalize());
            Files.createDirectories(records);
            Files.createDirectories(folder.getParent());
            // Never claim an existing directory, even an empty one or a previous instance.
            if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) throw new FileAlreadyExistsException(folder.toString());
            Properties record = new Properties();
            record.setProperty("path", folder.toString());
            record.setProperty("token", token);
            record.setProperty("world", worldKey);
            Path ownership = records.resolve(token + ".properties");
            try (var out = Files.newOutputStream(ownership, StandardOpenOption.CREATE_NEW)) {
                record.store(out, "Polar ephemeral instance ownership; no world content");
            }
            try { Files.createDirectory(folder); }
            catch (IOException failure) {
                // A competing creator may have won; its directory is not ours.
                try { Files.delete(ownership); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
            Files.writeString(folder.resolve(MARKER), token, StandardOpenOption.CREATE_NEW);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot reserve ephemeral world directory " + folder, e);
        }
    }

    public static void cleanup(Plugin plugin, Path directory) throws IOException {
        Path folder = directory.toAbsolutePath().normalize();
        Path records = plugin.getDataFolder().toPath().resolve("ephemeral-instances");
        if (!Files.isDirectory(records)) {
            if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing ownership record: " + folder);
            return;
        }
        boolean matched = false;
        try (var paths = Files.list(records)) {
            for (Path record : paths.filter(p -> p.getFileName().toString().endsWith(".properties")).toList()) {
                Properties properties = readRecord(record);
                if (folder.toString().equals(properties.getProperty("path"))) {
                    matched = true;
                    cleanupRecord(record, properties);
                }
            }
        }
        if (!matched && Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing ownership record: " + folder);
    }

    public static void cleanupOrphans(Plugin plugin) {
        Path records = plugin.getDataFolder().toPath().resolve("ephemeral-instances");
        if (!Files.isDirectory(records)) return;
        try (var paths = Files.list(records)) {
            for (Path record : paths.filter(p -> p.getFileName().toString().endsWith(".properties")).toList()) {
                try {
                    cleanupRecord(record, readRecord(record));
                } catch (IOException | RuntimeException e) {
                    plugin.getLogger().warning("Ephemeral cleanup pending for " + record + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot read ephemeral cleanup records: " + e.getMessage());
        }
    }

    private static Properties readRecord(Path record) throws IOException {
        if (Files.isSymbolicLink(record)) throw new IOException("Symbolic ownership record");
        Properties properties = new Properties();
        try (var in = Files.newInputStream(record)) { properties.load(in); }
        return properties;
    }

    private static void cleanupRecord(Path record, Properties properties) throws IOException {
        cleanupRecord(record, properties, Bukkit.getWorlds().stream().map(world -> world.getWorldFolder().toPath()).toList());
    }

    static void cleanupRecord(Path record, Properties properties, Collection<Path> activeDirectories) throws IOException {
        Path folder = Path.of(properties.getProperty("path")).toAbsolutePath().normalize();
        rejectSymlinkAncestors(folder);
        // An unload event is not proof of success. Check actual server registration here.
        for (Path activeDirectory : activeDirectories) {
            Path active = activeDirectory.toAbsolutePath().normalize();
            if (active.startsWith(folder)) throw new IOException("World directory is still active");
        }
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS)) {
            Path marker = folder.resolve(MARKER);
            if (Files.isSymbolicLink(marker) || !Files.readString(marker).equals(properties.getProperty("token"))) {
                throw new IOException("Ownership marker mismatch: " + folder);
            }
            // Keep the marker if any deletion fails, so cleanup remains retryable.
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.filter(p -> !p.equals(folder) && !p.equals(marker))
                        .sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
            Files.delete(marker);
            try { Files.delete(folder); }
            catch (IOException failure) {
                // Preserve retryability if, for example, another writer recreated a file after the walk.
                try {
                    rejectSymlinkAncestors(folder);
                    Files.writeString(marker, properties.getProperty("token"), StandardOpenOption.CREATE_NEW);
                } catch (IOException restore) { failure.addSuppressed(restore); }
                throw failure;
            }
        }
        Files.delete(record);
    }

    private static void rejectSymlinkAncestors(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic world path: " + current);
        }
    }
}
