package live.minehub.polarpaper;

import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.ephemeral.*;
import live.minehub.polarpaper.nms.VersionUtil;
import live.minehub.polarpaper.canvas_26_2.CanvasEphemeralBackend;
import live.minehub.polarpaper.core.util.FoliaUtil;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.world.WorldLoadEvent;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Shared lifecycle coordinator. State contains no World/Chunk references after an operation settles. */
final class EphemeralWorlds {
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final Set<NamespacedKey> CREATING = ConcurrentHashMap.newKeySet();

    private static final class State {
        final Path folder;
        final Map<ChunkCoordinates, Operation<DestroyChunkResult>> chunks = new HashMap<>();
        boolean destroying;
        Operation<Void> destruction;
        State(Path folder) { this.folder = folder; }
    }

    /** Cancelling/mutating a returned future must not cancel a native operation or release its coordination. */
    private static final class Operation<T> {
        final CompletableFuture<T> task;
        final CompletableFuture<T> result;
        Operation(CompletableFuture<T> task) { this.task = task; this.result = task.copy(); }
    }

    private EphemeralWorlds() { }

    static void checkSupport() {
        // Canvas also exposes Folia classes; select its checked native capability first.
        if (FoliaUtil.isCanvas()) {
            if (!Bukkit.getMinecraftVersion().equals("26.2")) throw new UnsupportedOperationException("Canvas ephemeral adapter requires 26.2");
            CanvasEphemeralBackend.verifySupport();
            return;
        }
        String version = Bukkit.getMinecraftVersion();
        if (!Bukkit.getName().equalsIgnoreCase("Paper") ||
                !(version.equals("26.1.2") || version.equals("26.2") || version.equals("26.3"))) {
            throw new UnsupportedOperationException("Ephemeral worlds require Paper 26.1.2/26.2/26.3 or patched Canvas 26.2; pure Folia is unsupported");
        }
        PaperEphemeralChunks.verifySupport();
    }

    static CompletableFuture<World> create(String name, Config config) {
        try {
            checkSupport();
            if (!config.ephemeral()) throw new IllegalArgumentException("createEphemeralWorld requires ephemeral: true");
            NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT).replace(' ', '_'), PolarPaper.getPlugin());
            if (key == null) throw new IllegalArgumentException("Invalid world name: " + name);
            if (!CREATING.add(key)) throw new IllegalStateException("World creation already in progress: " + key);
            Polar.setLoading(key, true);
            CompletableFuture<World> future = onMain(() -> {
                if (Bukkit.getWorld(key) != null) throw new IllegalStateException("World already exists: " + key);
                // Clone mutable location/map data: callers must not acquire a reference to the live world's config.
                Config isolated = config.toBuilder().spawn(config.spawn().clone()).gamerules(new HashMap<>(config.gamerules())).build();
                World world;
                if (FoliaUtil.isCanvas()) {
                    world = CanvasEphemeralBackend.create(PolarPaper.getPlugin(), key, isolated, VersionUtil.getPolarFeaturesWorldAccess());
                } else {
                    EphemeralGenerator generator = new EphemeralGenerator(isolated, VersionUtil.getPolarFeaturesWorldAccess());
                    WorldCreator creator = WorldCreator.ofKey(key).type(config.worldType()).environment(config.environment()).generator(generator);
                    world = VersionUtil.createNoSaveLevel(creator, isolated.spawn(), config.difficulty(), isolated.gamerules(), config.time()).join();
                }
                if (world == null) throw new IllegalStateException("Server did not create ephemeral world " + key);
                STATES.put(world.getUID(), new State(world.getWorldFolder().toPath()));
                if (!FoliaUtil.isCanvas()) new WorldLoadEvent(world).callEvent(); // Canvas WorldCreator fires this itself.
                return world;
            });
            future.whenComplete((world, error) -> { CREATING.remove(key); Polar.setLoading(key, false); });
            return future.copy();
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    static boolean isEphemeral(World world) {
        return world != null && world.getGenerator() instanceof EphemeralGenerator;
    }

    static void rejectSave(World world) {
        if (isEphemeral(world)) throw new IllegalArgumentException("Ephemeral worlds cannot be saved or exported");
    }

    private static State state(World world) {
        checkSupport();
        if (!isEphemeral(world)) throw new IllegalArgumentException("World is not ephemeral");
        return STATES.computeIfAbsent(world.getUID(), id -> new State(world.getWorldFolder().toPath()));
    }

    static CompletableFuture<DestroyChunkResult> destroyChunk(World world, int x, int z) {
        try {
            State state = state(world);
            ChunkCoordinates coordinates = new ChunkCoordinates(x, z);
            synchronized (state) {
                if (state.destroying) return CompletableFuture.completedFuture(DestroyChunkResult.WORLD_DESTROYING);
                var existing = state.chunks.get(coordinates);
                if (existing != null && !existing.task.isDone()) return existing.result;
                CompletableFuture<DestroyChunkResult> task = onMain(() -> {
                    if (Bukkit.getWorld(world.getUID()) != world) {
                        synchronized (state) {
                            if (!state.destroying) STATES.remove(world.getUID(), state);
                        }
                        return CompletableFuture.completedFuture(DestroyChunkResult.WORLD_DESTROYING);
                    }
                    if (FoliaUtil.isCanvas()) return CanvasEphemeralBackend.destroyChunk(world, x, z);
                    return CompletableFuture.completedFuture(PaperEphemeralChunks.destroy(((CraftWorld) world).getHandle(), x, z));
                }).thenCompose(future -> future);
                Operation<DestroyChunkResult> operation = new Operation<>(task);
                state.chunks.put(coordinates, operation);
                operation.task.whenComplete((result, error) -> { synchronized (state) { state.chunks.remove(coordinates, operation); } });
                return operation.result;
            }
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    static CompletableFuture<DestroyChunksResult> destroyChunks(World world, Collection<ChunkCoordinates> coordinates) {
        try {
            checkSupport();
            if (!isEphemeral(world)) throw new IllegalArgumentException("World is not ephemeral");
            Map<ChunkCoordinates, CompletableFuture<DestroyChunkResult>> futures = new LinkedHashMap<>();
            for (ChunkCoordinates coordinate : List.copyOf(coordinates)) {
                futures.computeIfAbsent(coordinate, c -> destroyChunk(world, c.x(), c.z()));
            }
            return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
                Map<ChunkCoordinates, DestroyChunkResult> results = new LinkedHashMap<>();
                futures.forEach((coordinate, future) -> results.put(coordinate, future.join()));
                return new DestroyChunksResult(results);
            });
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    static CompletableFuture<Void> destroyWorld(World world) {
        try {
            State state = state(world);
            synchronized (state) {
                if (state.destruction != null && !state.destruction.task.isCompletedExceptionally()) return state.destruction.result;
                state.destroying = true;
                CompletableFuture<?> pending = CompletableFuture.allOf(state.chunks.values().stream().map(operation -> operation.task).toArray(CompletableFuture[]::new));
                state.destruction = new Operation<>(pending.handle((result, error) -> null).thenCompose(ignored -> onMain(() -> {
                    if (Bukkit.getWorld(world.getUID()) != world) return CompletableFuture.<Void>completedFuture(null);
                    if (FoliaUtil.isCanvas()) return CanvasEphemeralBackend.unloadWorld(world);
                    PaperEphemeralChunks.unloadWorld(world);
                    return CompletableFuture.<Void>completedFuture(null);
                })).thenCompose(future -> future).thenCompose(ignored -> onMain(() -> {
                    if (Bukkit.getWorld(world.getUID()) == world) throw new IllegalStateException("World is still registered after unload");
                    Polar.stopAutoSaveTask(world.getKey());
                    ((EphemeralGenerator) world.getGenerator()).release();
                    EphemeralStorage.cleanup(PolarPaper.getPlugin(), state.folder);
                    STATES.remove(world.getUID(), state);
                    return null;
                })));
                return state.destruction.result;
            }
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    /** Always enqueue: callbacks/events may otherwise reenter a native unload in progress. */
    private static <T> CompletableFuture<T> onMain(Callable<T> action) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            Bukkit.getGlobalRegionScheduler().execute(PolarPaper.getPlugin(), () -> {
                try { future.complete(action.call()); }
                catch (Throwable error) { future.completeExceptionally(error); }
            });
        } catch (Throwable error) { future.completeExceptionally(error); }
        return future;
    }
}
