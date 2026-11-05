package live.minehub.polarpaper.core.ephemeral.edit;

/** Counts are processed writes and removed entities, not necessarily distinct changed blocks. Non-success may have progress. */
public record PatchChunkResult(Status status, long processedWrites, int removedEntities) {
    public enum Status { APPLIED, NOT_LOADED, NOT_FOUND, BUSY, BLOCKED, WORLD_DESTROYING, CAPACITY_EXCEEDED, FAILED }
    public static PatchChunkResult of(Status status) { return new PatchChunkResult(status, 0, 0); }
}
