package live.minehub.polarpaper.core.ephemeral.edit;

import live.minehub.polarpaper.core.ephemeral.ChunkCoordinates;
import java.util.Map;

public record PublishChunksResult(Map<ChunkCoordinates, PublishChunkResult> results) {
    public PublishChunksResult { results = Map.copyOf(results); }
}
