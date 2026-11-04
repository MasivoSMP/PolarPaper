package live.minehub.polarpaper.core.ephemeral;

import live.minehub.polarpaper.core.config.Config;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EphemeralConfigTest {
    @Test void defaultsToPersistentAndCopiesEphemeralFlag() {
        Config defaults = Config.Builder.defaults().build();
        assertFalse(defaults.ephemeral());
        Config ephemeral = defaults.toBuilder().ephemeral(true).build();
        assertTrue(ephemeral.ephemeral());
        assertTrue(ephemeral.toBuilder().build().ephemeral());
        assertFalse(defaults.ephemeral());
    }

    @Test void rejectsEverySavingConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> Config.Builder.defaults().ephemeral(true).saveOnStop(true).build());
        assertThrows(IllegalArgumentException.class, () -> Config.Builder.defaults().ephemeral(true).autoSaveIntervalTicks(20).build());
        assertDoesNotThrow(() -> Config.Builder.defaults().saveOnStop(true).autoSaveIntervalTicks(20).build());
    }

    @Test void readsExplicitAndInheritedYamlValues() {
        var yaml = new YamlConfiguration();
        yaml.set("worlds.dungeon.ephemeral", true);
        assertTrue(Config.readFromConfig(yaml, "dungeon").ephemeral());
        assertFalse(Config.readFromConfig(yaml, "normal").ephemeral());
        yaml.set("default.autosaveIntervalTicks", 20);
        assertThrows(IllegalArgumentException.class, () -> Config.readFromConfig(yaml, "dungeon"));
        yaml.set("worlds.dungeon.autosaveIntervalTicks", -1);
        assertTrue(Config.readFromConfig(yaml, "dungeon").ephemeral());
    }

    @Test void batchResultsAreImmutableSnapshots() {
        var coordinate = new ChunkCoordinates(4, -8);
        var input = new HashMap<>(Map.of(coordinate, DestroyChunkResult.BLOCKED));
        var result = new DestroyChunksResult(input);
        input.clear();
        assertEquals(DestroyChunkResult.BLOCKED, result.results().get(coordinate));
        assertThrows(UnsupportedOperationException.class, () -> result.results().clear());
    }
}
