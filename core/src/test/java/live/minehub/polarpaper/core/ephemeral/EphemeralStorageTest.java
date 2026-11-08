package live.minehub.polarpaper.core.ephemeral;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class EphemeralStorageTest {
    @TempDir Path root;

    private Properties ownership(Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(".polar-ephemeral"), "owned-token");
        Properties properties = new Properties();
        properties.setProperty("path", folder.toString());
        properties.setProperty("token", "owned-token");
        return properties;
    }

    private Path record() throws IOException { return Files.createTempFile(root, "ownership-", ".properties"); }

    @Test void deletesOwnedInstanceButNotItsParent() throws IOException {
        Path folder = root.resolve("world/dimensions/polarpaper/instance");
        var properties = ownership(folder);
        Files.createDirectories(folder.resolve("data"));
        Files.writeString(folder.resolve("data/server-metadata"), "temporary");
        Path record = record();
        // The overworld is an active ancestor of every custom dimension, not the same active dimension.
        EphemeralStorage.cleanupRecord(record, properties, List.of(root.resolve("world")));
        assertFalse(Files.exists(folder));
        assertFalse(Files.exists(record));
        assertTrue(Files.isDirectory(root.resolve("world")));
    }

    @Test void neverDeletesAnActiveWorldOrAncestorOfOne() throws IOException {
        Path folder = root.resolve("instance");
        var properties = ownership(folder);
        Path record = record();
        assertThrows(IOException.class, () -> EphemeralStorage.cleanupRecord(record, properties, List.of(folder)));
        assertThrows(IOException.class, () -> EphemeralStorage.cleanupRecord(record, properties, List.of(folder.resolve("active-child"))));
        assertTrue(Files.exists(folder.resolve(".polar-ephemeral")));
        assertTrue(Files.exists(record));
    }

    @Test void refusesUnownedOrMismatchedDirectories() throws IOException {
        Path folder = root.resolve("instance");
        var properties = ownership(folder);
        properties.setProperty("token", "not-our-token");
        Path record = record();
        assertThrows(IOException.class, () -> EphemeralStorage.cleanupRecord(record, properties, List.of()));
        assertTrue(Files.exists(folder));
        assertTrue(Files.exists(record));
    }

    @Test void refusesSymbolicAncestorsWithoutTouchingTarget() throws IOException {
        Path folder = root.resolve("instance");
        var properties = ownership(folder);
        Path link = root.resolve("link");
        createSymbolicLink(link, folder);
        properties.setProperty("path", link.toString());
        assertThrows(IOException.class, () -> EphemeralStorage.cleanupRecord(record(), properties, List.of()));
        assertTrue(Files.exists(folder.resolve(".polar-ephemeral")));
    }

    @Test void doesNotFollowLinksInsideAnOwnedInstance() throws IOException {
        Path folder = root.resolve("instance");
        var properties = ownership(folder);
        Path outside = Files.writeString(root.resolve("user-file"), "keep");
        createSymbolicLink(folder.resolve("link"), outside);
        EphemeralStorage.cleanupRecord(record(), properties, List.of());
        assertEquals("keep", Files.readString(outside));
    }

    @Test void cleansRecordWhenInstanceWasAlreadyRemoved() throws IOException {
        Path folder = root.resolve("absent");
        Properties properties = new Properties();
        properties.setProperty("path", folder.toString());
        properties.setProperty("token", "owned-token");
        Path record = record();
        EphemeralStorage.cleanupRecord(record, properties, List.of());
        assertFalse(Files.exists(record));
    }

    private static void createSymbolicLink(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (FileSystemException ex) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw ex;
            assumeTrue(false, "Symbolic links are unavailable: " + ex.getMessage());
        } catch (UnsupportedOperationException ex) {
            assumeTrue(false, "Symbolic links are unsupported: " + ex.getMessage());
        }
    }
}
