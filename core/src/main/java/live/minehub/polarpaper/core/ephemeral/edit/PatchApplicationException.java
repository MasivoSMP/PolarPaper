package live.minehub.polarpaper.core.ephemeral.edit;

/** Technical failure, possibly after partial application. No rollback is implied. */
public final class PatchApplicationException extends RuntimeException {
    private final PatchChunkResult progress;
    public PatchApplicationException(PatchChunkResult progress, Throwable cause) { super("Patch failed after " + progress.processedWrites() + " writes", cause); this.progress = progress; }
    public PatchChunkResult progress() { return progress; }
}
