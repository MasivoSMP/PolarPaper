package live.minehub.polarpaper.core.ephemeral.edit;

/** Per-world admission/work limits. Rejections are explicit; there is no unbounded work queue. */
public record ChunkEditingOptions(int maxPublications, long maxPendingBytes, int maxQueuedEdits, int writesPerTick) {
    public static final ChunkEditingOptions DEFAULT = new ChunkEditingOptions(256, 64L * 1024 * 1024, 256, 8192);
    public ChunkEditingOptions {
        if (maxPublications < 1 || maxPublications > 4096 || maxPendingBytes < 1024 || maxPendingBytes > 1024L * 1024 * 1024 ||
                maxQueuedEdits < 1 || maxQueuedEdits > 4096 || writesPerTick < 1 || writesPerTick > 32768)
            throw new IllegalArgumentException("Invalid editing limits");
    }
}
