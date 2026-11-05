package live.minehub.polarpaper.core.ephemeral.edit;

import live.minehub.polarpaper.core.ephemeral.ChunkCoordinates;
import java.util.Map;

/** A batch is partial, not atomic. Technical failures may occur after other chunks have succeeded. */
public record PatchChunksResult(Map<ChunkCoordinates, PatchChunkResult> results) {
    public PatchChunksResult { results = Map.copyOf(results); }
}
