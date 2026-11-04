package live.minehub.polarpaper.core.ephemeral;

import java.util.Map;

/** A batch is partial, never an atomic transaction. Duplicate coordinates are collapsed. */
public record DestroyChunksResult(Map<ChunkCoordinates, DestroyChunkResult> results) {
    public DestroyChunksResult {
        results = Map.copyOf(results);
    }
}
