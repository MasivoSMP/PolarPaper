package live.minehub.polarpaper.core.ephemeral;

/** Chunk coordinates, not block coordinates. Does not load or retain a chunk. */
public record ChunkCoordinates(int x, int z) {
}
