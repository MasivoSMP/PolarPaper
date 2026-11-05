package live.minehub.polarpaper.core.ephemeral.edit;

import live.minehub.polarpaper.core.ephemeral.ChunkCoordinates;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ChunkModelsTest {
    @Test void patternIsImmutableAndUsesAirForOmittedBlocks() {
        var builder = BlockPattern.builder(2, 2, 2).setBlock(1, 1, 1, "minecraft:stone");
        var pattern = builder.build(); builder.setBlock(1, 1, 1, "minecraft:dirt");
        assertEquals("minecraft:stone", pattern.block(1, 1, 1));
        assertEquals("minecraft:air", pattern.block(0, 0, 0));
        assertThrows(UnsupportedOperationException.class, () -> pattern.palette().add("x"));
        assertThrows(IndexOutOfBoundsException.class, () -> pattern.block(2, 0, 0));
    }
    @Test void patternRejectsInvalidVolumeAndPaletteMetadata() {
        assertThrows(IllegalArgumentException.class, () -> BlockPattern.builder(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> BlockPattern.builder(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
        var builder = BlockPattern.builder(1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> builder.setBlock(0, 0, 0, "x".repeat(4097)));
    }
    @Test void preparedChunkSnapshotsBlocksAndQuartBiomes() {
        var builder = PreparedChunk.builder(-64, 320).setBlock(0, -64, 15, "minecraft:stone").setBiome(3, -61, 7, "minecraft:desert");
        var chunk = builder.build(); builder.fillBiome("minecraft:plains").setBlock(0, -64, 15, "minecraft:air");
        assertEquals("minecraft:stone", chunk.blocks().block(0, 0, 15));
        assertEquals("minecraft:desert", chunk.biome(0, -64, 4));
        assertEquals("minecraft:plains", chunk.biome(4, -64, 4));
        assertTrue(chunk.estimatedBytes() > 0);
        assertThrows(IllegalArgumentException.class, () -> PreparedChunk.builder(-63, 320));
        assertThrows(IndexOutOfBoundsException.class, () -> chunk.biome(0, 320, 0));
    }
    @Test void sparsePatchKeepsOrderAndLastWriteWins() {
        var builder = ChunkPatch.builder().setRegion(0, 64, 0, 2, 65, 2, "minecraft:stone").setBlock(1, 64, 1, "minecraft:gold_block");
        var patch = builder.build(); builder.setBlock(0, 64, 0, "minecraft:air");
        assertEquals(5, patch.writes()); assertEquals(2, patch.regions().size());
        assertEquals("minecraft:gold_block", patch.regions().getLast().state());
        assertThrows(UnsupportedOperationException.class, () -> patch.regions().clear());
        assertThrows(IllegalArgumentException.class, () -> ChunkPatch.builder().setBlock(16, 0, 0, "minecraft:stone"));
        assertThrows(IllegalArgumentException.class, () -> ChunkPatch.builder().setRegion(0, Integer.MIN_VALUE, 0, 16, Integer.MAX_VALUE, 16, "minecraft:air"));
    }
    @Test void boundsAndTransformsUseExclusiveMaxAndCheckedArithmetic() {
        var bounds = new BlockBounds(-1, 60, -1, 1, 62, 1);
        assertEquals(8, bounds.volume()); assertTrue(bounds.contains(-1, 60, -1)); assertFalse(bounds.contains(1, 60, 0));
        assertThrows(IllegalArgumentException.class, () -> new BlockBounds(0, 1, 0, 1, 1, 1));
        assertThrows(ArithmeticException.class, () -> new BlockPosition(Integer.MAX_VALUE, 0, 0).add(1, 0, 0));
        assertEquals(new BlockPosition(2, 4, 0), new PatternOptions(1, false, false, PatchOptions.DEFAULT).transform(0, 4, 0, 2, 3));
        assertEquals(new BlockPosition(1, 4, 0), new PatternOptions(0, true, false, PatchOptions.DEFAULT).transform(0, 4, 0, 2, 3));
        assertEquals(new BlockPosition(0, 4, 1), new PatternOptions(3, false, false, PatchOptions.DEFAULT).transform(0, 4, 0, 2, 3));
    }
    @Test void limitsRejectZeroAndUnboundedBudgets() {
        assertThrows(IllegalArgumentException.class, () -> new PatchOptions(PatchOptions.LoadPolicy.REQUIRE_LOADED, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkEditingOptions(0, 1024, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ChunkEditingOptions(1, Long.MAX_VALUE, 1, 1));
    }
    @Test void publicationStoreBoundsReleasesAndClosesWithoutCoordinateHistory() {
        var store = new ChunkContentStore(null, new ChunkEditingOptions(1, 1_048_576, 2, 1));
        var chunk = PreparedChunk.builder(0, 4).build();
        var content = new ChunkContentStore.Content(chunk, Map.of(), Map.of());
        var first = new ChunkCoordinates(1, 2); var second = new ChunkCoordinates(2, 3);
        try {
            assertEquals(PublishChunkResult.PUBLISHED, store.publish(first, content));
            assertEquals(PublishChunkResult.ALREADY_PUBLISHED, store.publish(first, content));
            assertEquals(PublishChunkResult.CAPACITY_EXCEEDED, store.publish(second, content));
            assertTrue(store.pendingBytes() > 0); assertTrue(store.discard(first)); assertEquals(0, store.pendingBytes());
            assertEquals(PublishChunkResult.PUBLISHED, store.publish(second, content));
            store.startClosing(); assertEquals(PublishChunkResult.WORLD_DESTROYING, store.publish(first, content));
        } finally { store.release(); }
        assertEquals(0, store.pendingBytes()); assertFalse(store.hasPublication(second));
        store.release(); // Idempotent global quota release.
    }
    @Test void batchAndWarningCollectionsAreSnapshots() {
        var results = new java.util.HashMap<ChunkCoordinates, PublishChunkResult>();
        results.put(new ChunkCoordinates(0, 0), PublishChunkResult.PUBLISHED);
        var batch = new PublishChunksResult(results); results.clear(); assertEquals(1, batch.results().size());
        var warnings = new java.util.ArrayList<>(java.util.List.of("ignored"));
        var imported = new SchematicImport(BlockPattern.builder(1, 1, 1).build(), new BlockPosition(0, 0, 0), 1, warnings);
        warnings.clear(); assertEquals(1, imported.warnings().size());
    }
}
