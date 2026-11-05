package live.minehub.polarpaper.core.ephemeral.edit;

import live.minehub.polarpaper.core.ephemeral.ChunkCoordinates;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/** No loading, region creation, or tickets. Lock order is native ticket -> native scheduling -> payload store. */
public final class ChunkPublicationAccess {
    private ChunkPublicationAccess() { }
    public static PublishChunkResult publish(ServerLevel level, int x, int z, ChunkContentStore store, ChunkContentStore.Content content) {
        var scheduler = level.moonrise$getChunkTaskScheduler();
        var manager = scheduler.chunkHolderManager;
        var ticket = manager.ticketLockArea.lock(x, z);
        try {
            var scheduling = scheduler.schedulingLockArea.lock(x, z);
            try {
                var holder = manager.getChunkHolder(x, z);
                if (holder != null) return holder.getCurrentChunk() instanceof LevelChunk ? PublishChunkResult.CHUNK_EXISTS : PublishChunkResult.GENERATION_STARTED;
                return store.publish(new ChunkCoordinates(x, z), content);
            } finally { scheduler.schedulingLockArea.unlock(scheduling); }
        } finally { manager.ticketLockArea.unlock(ticket); }
    }
    public static DiscardPublicationResult discard(ServerLevel level, int x, int z, ChunkContentStore store) {
        var scheduler = level.moonrise$getChunkTaskScheduler();
        var manager = scheduler.chunkHolderManager;
        var ticket = manager.ticketLockArea.lock(x, z);
        try {
            var scheduling = scheduler.schedulingLockArea.lock(x, z);
            try {
                var coordinate = new ChunkCoordinates(x, z);
                if (!store.hasPublication(coordinate)) return DiscardPublicationResult.NOT_PUBLISHED;
                if (manager.getChunkHolder(x, z) != null) return DiscardPublicationResult.BUSY;
                store.discard(coordinate); return DiscardPublicationResult.DISCARDED;
            } finally { scheduler.schedulingLockArea.unlock(scheduling); }
        } finally { manager.ticketLockArea.unlock(ticket); }
    }
}
