package live.minehub.polarpaper.core.ephemeral.edit;

/** Concurrent generation callback. Must not access live chunks, do IO, wait on futures, or call Polar editing APIs. */
@FunctionalInterface
public interface ChunkContentProvider {
    /** Return null for a void chunk. Exceptions are generation failures, never silently replaced with void. */
    PreparedChunk prepare(ChunkGenerationContext context);
}
