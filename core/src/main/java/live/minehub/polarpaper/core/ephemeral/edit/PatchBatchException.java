package live.minehub.polarpaper.core.ephemeral.edit;

import live.minehub.polarpaper.core.ephemeral.ChunkCoordinates;
import java.util.Map;

/** Settled batch outcomes remain inspectable after a technical failure. No rollback has been performed. */
public final class PatchBatchException extends RuntimeException {
    private final Map<ChunkCoordinates, PatchChunkResult> results;
    private final Map<ChunkCoordinates, Throwable> errors;
    public PatchBatchException(Map<ChunkCoordinates, PatchChunkResult> results, Map<ChunkCoordinates, Throwable> errors) {
        super("Patch batch failed in " + errors.size() + " chunks", errors.values().iterator().next());
        this.results = Map.copyOf(results); this.errors = Map.copyOf(errors);
    }
    public Map<ChunkCoordinates, PatchChunkResult> results() { return results; }
    public Map<ChunkCoordinates, Throwable> errors() { return errors; }
}
