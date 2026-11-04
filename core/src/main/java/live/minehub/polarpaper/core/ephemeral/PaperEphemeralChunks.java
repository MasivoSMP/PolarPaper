package live.minehub.polarpaper.core.ephemeral;

import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.world.WorldUnloadEvent;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;

/**
 * Paper 26.x adapter. Runs Moonrise's three native unload stages for exactly one holder.
 * Never clears holder fields itself and never drains the world's unrelated unload queue.
 */
public final class PaperEphemeralChunks {
    private static final ThreadLocal<World> UNLOADING = new ThreadLocal<>();
    private static final Method STAGE1 = method(NewChunkHolder.class, "unloadStage1");
    private static final Method STAGE2 = method(NewChunkHolder.class, "unloadStage2", STAGE1.getReturnType());
    private static final Method STAGE3 = method(NewChunkHolder.class, "unloadStage3");
    private static final Method DEQUEUE = method(NewChunkHolder.class, "removeFromUnloadQueue");
    private static final Method REMOVE = method(ChunkHolderManager.class, "removeChunkHolder", NewChunkHolder.class);
    private static final Field WORLDS = worldsField();
    private static final Field UNLOAD_QUEUE = field(ChunkHolderManager.class, "unloadQueue");
    private static final Method REMOVE_QUEUED = method(UNLOAD_QUEUE.getType(), "removeChunk", int.class, int.class);

    private PaperEphemeralChunks() { }

    /** Resolve all internal entry points before creating a world. */
    public static void verifySupport() { }

    /** Like CraftServer.unloadWorld(false), but closure failures must propagate before deregistration/deletion. */
    public static void unloadWorld(World world) throws Exception {
        TickThread.ensureTickThread("Ephemeral world destruction must run on the Paper main thread");
        ServerLevel level = ((CraftWorld) world).getHandle();
        if (level.dimension() == net.minecraft.world.level.Level.OVERWORLD || !world.getPlayers().isEmpty()) {
            throw new IllegalStateException("Cannot destroy a primary world or a world containing players");
        }
        UNLOADING.set(world);
        try {
            if (!new WorldUnloadEvent(world).callEvent()) throw new IllegalStateException("World unload was cancelled");
        } finally { UNLOADING.remove(); }
        if (!world.getPlayers().isEmpty()) throw new IllegalStateException("Players entered during WorldUnloadEvent");
        var scheduler = level.moonrise$getChunkTaskScheduler();
        if (!scheduler.halt(true, TimeUnit.SECONDS.toNanos(60))) throw new IllegalStateException("Chunk tasks did not halt");
        MoonriseRegionFileIO.flush(level);
        if (!scheduler.haltIO(true, TimeUnit.SECONDS.toNanos(60))) throw new IllegalStateException("Chunk IO did not halt");
        // Native close logs some IO errors instead of throwing. Close caches explicitly first.
        for (var type : MoonriseRegionFileIO.RegionFileType.values()) {
            MoonriseRegionFileIO.getControllerFor(level, type).getCache().close();
        }
        level.getChunkSource().close(false);
        io.papermc.paper.FeatureHooks.closeEntityManager(level, false);
        CraftServer server = (CraftServer) Bukkit.getServer();
        @SuppressWarnings("unchecked") Map<String, World> worlds = (Map<String, World>) WORLDS.get(server);
        worlds.remove(world.getName().toLowerCase(Locale.ROOT));
        server.getServer().removeLevel(level);
    }

    private static Field worldsField() {
        return field(CraftServer.class, "worlds");
    }

    public static boolean isUnloading(World world) { return UNLOADING.get() == world; }

    private static Field field(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported Paper world registry", e); }
    }

    public static void configure(ServerLevel level) {
        level.paperConfig().chunks.maxAutoSaveChunksPerTick = 0;
        // A separate per-world map, not the inherited/default configuration map.
        var limits = new Reference2IntOpenHashMap<net.minecraft.world.entity.EntityType<?>>();
        for (var type : BuiltInRegistries.ENTITY_TYPE) limits.put(type, 0);
        level.paperConfig().chunks.entityPerChunkSaveLimit = limits;
        level.getWorld().setAutoSave(false);
    }

    public static DestroyChunkResult destroy(ServerLevel level, int x, int z) {
        TickThread.ensureTickThread("Ephemeral chunk destruction must run on the Paper main thread");
        configure(level);
        var scheduler = level.moonrise$getChunkTaskScheduler();
        var manager = scheduler.chunkHolderManager;
        manager.processTicketUpdates();
        Object unloadState;
        NewChunkHolder holder;
        var ticketLock = manager.ticketLockArea.lock(x, z);
        try {
            var schedulingLock = scheduler.schedulingLockArea.lock(x, z);
            try {
                holder = manager.getChunkHolder(x, z);
                if (holder == null) return DestroyChunkResult.ALREADY_ABSENT;
                if (holder.isSafeToUnload() != null) return DestroyChunkResult.BLOCKED;
                if (holder.getCurrentChunk() != null && !(holder.getCurrentChunk() instanceof LevelChunk)) return DestroyChunkResult.BLOCKED;
                if (holder.getCurrentChunk() == null && holder.getPoiChunk() != null) return DestroyChunkResult.BLOCKED;
                var entities = holder.getEntityChunk();
                if (entities != null) {
                    // Native unload retains non-saveable entities as transient slices. Never force-remove them.
                    for (var entity : entities.getAllEntities()) {
                        if (!entity.shouldBeSaved()) return DestroyChunkResult.BLOCKED;
                    }
                }
                if (holder.getCurrentChunk() instanceof LevelChunk chunk) chunk.mustNotSave = true;
                invoke(DEQUEUE, holder);
                try { invoke(REMOVE_QUEUED, UNLOAD_QUEUE.get(manager), x, z); }
                catch (IllegalAccessException e) { throw new IllegalStateException(e); }
                unloadState = invoke(STAGE1, holder);
                if (unloadState == null) {
                    invoke(REMOVE, manager, holder);
                    return DestroyChunkResult.DESTROYED;
                }
            } finally {
                scheduler.schedulingLockArea.unlock(schedulingLock);
            }
        } finally {
            manager.ticketLockArea.unlock(ticketLock);
        }

        // Native stage 2 fires lifecycle hooks, unregisters ticks/block entities/entities/POI.
        Boolean previous = manager.blockTicketUpdates();
        try { invoke(STAGE2, holder, unloadState); }
        finally { manager.unblockTicketUpdates(previous); }

        ticketLock = manager.ticketLockArea.lock(x, z);
        try {
            var schedulingLock = scheduler.schedulingLockArea.lock(x, z);
            try {
                if ((boolean) invoke(STAGE3, holder)) {
                    invoke(REMOVE, manager, holder);
                    return DestroyChunkResult.DESTROYED;
                }
                // Reentrant server/plugin work prevented final removal. No retry and no foreign ticket removal.
                return DestroyChunkResult.BLOCKED;
            } finally { scheduler.schedulingLockArea.unlock(schedulingLock); }
        } finally { manager.ticketLockArea.unlock(ticketLock); }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try {
            Method method = owner.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unsupported Paper chunk lifecycle: " + name, e);
        }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try { return method.invoke(target, arguments); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Paper chunk lifecycle failed: " + method.getName(), e); }
    }
}
