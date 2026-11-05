package live.minehub.polarpaper;

import live.minehub.polarpaper.canvas_26_2.CanvasEphemeralBackend;
import live.minehub.polarpaper.core.ephemeral.*;
import live.minehub.polarpaper.core.ephemeral.edit.*;
import live.minehub.polarpaper.core.util.FoliaUtil;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.*;

/** Public API implementation. Live writes only occur in bounded, owned-region steps. */
final class EphemeralEdits {
    private static final Budget GLOBAL = new Budget(32768);
    private static final Map<UUID, Budget> BUDGETS = new ConcurrentHashMap<>();
    private static final Executor PREPARATION = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), runnable -> {
        Thread thread = new Thread(runnable, "polar-chunk-preparation"); thread.setDaemon(true); return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private static final ChunkCoordinates PREPARATION_COORDINATE = new ChunkCoordinates(Integer.MIN_VALUE, Integer.MIN_VALUE);
    private EphemeralEdits() { }

    static ChunkContentStore store(World world) {
        ChunkContentStore.checkNotPreparing();
        EphemeralWorlds.checkSupport();
        if (!(world.getGenerator() instanceof EphemeralGenerator generator)) throw new IllegalArgumentException("Editing requires a Polar ephemeral world");
        if (FoliaUtil.isCanvas()) CanvasEphemeralBackend.verifyEditingSupport();
        return generator.content();
    }
    static void release(UUID world) { BUDGETS.remove(world); }
    private static ServerLevel level(World world) { return ((CraftWorld) world).getHandle(); }
    private static void coordinate(ChunkCoordinates c) {
        Objects.requireNonNull(c);
        if (c.x() < -1_875_000 || c.x() >= 1_875_000 || c.z() < -1_875_000 || c.z() >= 1_875_000)
            throw new IllegalArgumentException("Chunk exceeds Minecraft horizontal limits");
    }

    static CompletableFuture<PublishChunkResult> publish(World world, int x, int z, PreparedChunk chunk) {
        try {
            ChunkContentStore store = store(world);
            ChunkCoordinates c = new ChunkCoordinates(x, z); coordinate(c); Objects.requireNonNull(chunk);
            return EphemeralWorlds.admission(world, c, chunk.estimatedBytes() * 2, () -> {
                var content = store.resolve(chunk, world.getMinHeight(), world.getMaxHeight());
                return ChunkPublicationAccess.publish(level(world), x, z, store, content);
            }, PublishChunkResult.BUSY, PublishChunkResult.WORLD_DESTROYING, PublishChunkResult.CAPACITY_EXCEEDED);
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }
    static CompletableFuture<PublishChunksResult> publish(World world, Map<ChunkCoordinates, PreparedChunk> input) {
        try {
            var snapshot = Map.copyOf(input); checkBatch(snapshot.size()); store(world);
            // Validate every payload before accepting any publication from this batch.
            long bytes = snapshot.values().stream().mapToLong(chunk -> chunk.estimatedBytes() * 2).sum();
            return prepare(world, bytes, () -> {
                Map<ChunkCoordinates, ChunkContentStore.Content> contents = new LinkedHashMap<>();
                var store = store(world);
                snapshot.forEach((c, chunk) -> { coordinate(c); contents.put(c, store.resolve(chunk, world.getMinHeight(), world.getMaxHeight())); });
                Map<ChunkCoordinates, CompletableFuture<PublishChunkResult>> futures = new LinkedHashMap<>();
                contents.forEach((c, content) -> futures.put(c, EphemeralWorlds.admission(world, c, content.bytes(),
                        () -> ChunkPublicationAccess.publish(level(world), c.x(), c.z(), store, content), PublishChunkResult.BUSY, PublishChunkResult.WORLD_DESTROYING, PublishChunkResult.CAPACITY_EXCEEDED)));
                return all(futures).thenApply(PublishChunksResult::new);
            }).thenCompose(future -> future);
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }
    static CompletableFuture<DiscardPublicationResult> discard(World world, int x, int z) {
        try {
            ChunkContentStore store = store(world); ChunkCoordinates c = new ChunkCoordinates(x, z); coordinate(c);
            return EphemeralWorlds.admission(world, c, () -> ChunkPublicationAccess.discard(level(world), x, z, store), DiscardPublicationResult.BUSY, DiscardPublicationResult.WORLD_DESTROYING);
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }
    static CompletableFuture<GenerateChunkResult> generate(World world, int x, int z) {
        try {
            var store = store(world); ChunkCoordinates c = new ChunkCoordinates(x, z); coordinate(c);
            return EphemeralWorlds.edit(world, c, 0, () -> {
                if (!store.hasPublication(c)) return CompletableFuture.completedFuture(GenerateChunkResult.NOT_PUBLISHED);
                return world.getChunkAtAsync(x, z, true).thenCompose(chunk -> live.minehub.polarpaper.core.util.TaskFutures.runRegion(PolarPaper.getPlugin(), world, x, z, () -> {
                    if (chunk == null) throw new IllegalStateException("Server returned no generated chunk");
                    store.checkRestoration();
                    store.discard(c); return GenerateChunkResult.GENERATED;
                }));
            }, GenerateChunkResult.BUSY, GenerateChunkResult.WORLD_DESTROYING, GenerateChunkResult.CAPACITY_EXCEEDED);
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    static CompletableFuture<PatchChunkResult> patch(World world, int x, int z, ChunkPatch patch, PatchOptions options) {
        try {
            var input = Map.of(new ChunkCoordinates(x, z), patch);
            return validatedPatches(world, input, options, null).thenCompose(futures -> futures.values().iterator().next());
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }
    static CompletableFuture<PatchChunksResult> patches(World world, Map<ChunkCoordinates, ChunkPatch> input, PatchOptions options) {
        try { return validatedPatches(world, Map.copyOf(input), options, null).thenCompose(EphemeralEdits::patchResults); }
        catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    private static CompletableFuture<Map<ChunkCoordinates, CompletableFuture<PatchChunkResult>>> validatedPatches(
            World world, Map<ChunkCoordinates, ChunkPatch> input, PatchOptions options, Map<ChunkCoordinates, BlockBounds> clear) {
        Objects.requireNonNull(options); checkBatch(input.size()); store(world);
        long bytes = input.values().stream().mapToLong(ChunkPatch::estimatedBytes).sum();
        return prepare(world, bytes, () -> {
            Map<String, BlockData> palette = new HashMap<>();
            long totalWrites = 0, totalBytes = 0;
            for (var entry : input.entrySet()) {
                coordinate(entry.getKey());
                for (var region : entry.getValue().regions()) {
                    if (region.minY() < world.getMinHeight() || region.maxY() > world.getMaxHeight()) throw new IllegalArgumentException("Patch exceeds world height");
                    palette.computeIfAbsent(region.state(), Bukkit::createBlockData);
                }
                totalWrites += entry.getValue().writes(); totalBytes += entry.getValue().estimatedBytes();
            }
            if (totalWrites > BlockPattern.MAX_BLOCKS || totalBytes > store(world).options().maxPendingBytes()) throw new IllegalArgumentException("Batch exceeds payload/write limit");
            Map<String, BlockData> resolved = Map.copyOf(palette);
            Map<ChunkCoordinates, CompletableFuture<PatchChunkResult>> futures = new LinkedHashMap<>();
            input.forEach((c, patch) -> futures.put(c, EphemeralWorlds.edit(world, c, patch.estimatedBytes(), () -> {
                var content = store(world);
                var holder = level(world).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(c.x(), c.z());
                if (content.hasPublication(c) || (holder != null && !(holder.getCurrentChunk() instanceof net.minecraft.world.level.chunk.LevelChunk)))
                    return CompletableFuture.completedFuture(PatchChunkResult.of(PatchChunkResult.Status.BUSY));
                if (holder == null && options.loadPolicy() == PatchOptions.LoadPolicy.LOAD_EXISTING)
                    return CompletableFuture.completedFuture(PatchChunkResult.of(PatchChunkResult.Status.NOT_FOUND));
                if (options.loadPolicy() == PatchOptions.LoadPolicy.REQUIRE_LOADED) {
                    if (!world.isChunkLoaded(c.x(), c.z())) return CompletableFuture.completedFuture(PatchChunkResult.of(PatchChunkResult.Status.NOT_LOADED));
                    return new Work(world, c, patch, resolved, options, clear == null ? null : clear.get(c)).start();
                }
                // gen=false: never create a new chunk, including in an otherwise void world.
                return world.getChunkAtAsync(c.x(), c.z(), false).thenCompose(chunk -> chunk == null
                        ? CompletableFuture.completedFuture(PatchChunkResult.of(PatchChunkResult.Status.NOT_FOUND))
                        : new Work(world, c, patch, resolved, options, clear == null ? null : clear.get(c)).start());
            }, PatchChunkResult.of(PatchChunkResult.Status.BUSY), PatchChunkResult.of(PatchChunkResult.Status.WORLD_DESTROYING), PatchChunkResult.of(PatchChunkResult.Status.CAPACITY_EXCEEDED))));
            return futures;
        });
    }

    static CompletableFuture<PatchChunksResult> paste(World world, BlockPosition origin, BlockPattern pattern, PatternOptions options) {
        try {
            Objects.requireNonNull(origin); Objects.requireNonNull(pattern); Objects.requireNonNull(options); store(world);
            return prepare(world, pattern.estimatedBytes(), () -> {
                int width = options.quarterTurns() % 2 == 0 ? pattern.width() : pattern.length();
                int length = options.quarterTurns() % 2 == 0 ? pattern.length() : pattern.width();
                var bounds = new BlockBounds(origin.x(), origin.y(), origin.z(), Math.addExact(origin.x(), width),
                        Math.addExact(origin.y(), pattern.height()), Math.addExact(origin.z(), length));
                if (bounds.minY() < world.getMinHeight() || bounds.maxY() > world.getMaxHeight()) throw new IllegalArgumentException("Pattern exceeds world height");
                Map<String, String> transformed = new HashMap<>();
                StructureRotation rotation = switch (options.quarterTurns()) {
                    case 0 -> StructureRotation.NONE;
                    case 1 -> StructureRotation.CLOCKWISE_90;
                    case 2 -> StructureRotation.CLOCKWISE_180;
                    default -> StructureRotation.COUNTERCLOCKWISE_90;
                };
                for (String state : pattern.palette()) {
                    BlockData data = Bukkit.createBlockData(state);
                    if (options.mirrorX()) data.mirror(Mirror.FRONT_BACK);
                    data.rotate(rotation); transformed.put(state, data.getAsString());
                }
                Map<ChunkCoordinates, ChunkPatch.Builder> builders = new LinkedHashMap<>();
                long metadataBytes = 0;
                for (int y = 0; y < pattern.height(); y++) for (int z = 0; z < pattern.length(); z++) {
                    int x = 0;
                    while (x < pattern.width()) {
                        String state = pattern.block(x, y, z);
                        BlockPosition offset = options.transform(x, y, z, pattern.width(), pattern.length());
                        BlockPosition target = origin.add(offset.x(), offset.y(), offset.z());
                        int end = x + 1;
                        if (options.quarterTurns() == 0 && !options.mirrorX()) {
                            while (end < pattern.width() && (target.x() & 15) + end - x < 16 && pattern.block(end, y, z).equals(state)) end++;
                        }
                        if (!options.ignoreAir() || !BlockPattern.isAir(state)) {
                            ChunkCoordinates c = new ChunkCoordinates(target.x() >> 4, target.z() >> 4); coordinate(c);
                            metadataBytes += 112L + transformed.get(state).length() * 2L;
                            if (metadataBytes > store(world).options().maxPendingBytes()) throw new RejectedExecutionException("Pattern metadata exceeds world capacity");
                            builders.computeIfAbsent(c, ignored -> ChunkPatch.builder()).setRegion(target.x() & 15, target.y(), target.z() & 15,
                                    (target.x() & 15) + end - x, target.y() + 1, (target.z() & 15) + 1, transformed.get(state));
                        }
                        x = end;
                    }
                }
                checkBatch(builders.size());
                Map<ChunkCoordinates, ChunkPatch> patches = new LinkedHashMap<>(); builders.forEach((c, builder) -> patches.put(c, builder.build()));
                return validatedPatches(world, patches, options.patchOptions(), null).thenCompose(EphemeralEdits::patchResults);
            }).thenCompose(future -> future);
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }
    static CompletableFuture<PatchChunksResult> clear(World world, BlockBounds bounds, PatchOptions options) {
        try {
            Objects.requireNonNull(bounds); Objects.requireNonNull(options); store(world);
            if (bounds.volume() > BlockPattern.MAX_BLOCKS) throw new IllegalArgumentException("Volume exceeds write limit");
            Map<ChunkCoordinates, ChunkPatch> patches = new LinkedHashMap<>();
            Map<ChunkCoordinates, BlockBounds> parts = new LinkedHashMap<>();
            for (int z = bounds.minZ() >> 4; z <= (bounds.maxZ() - 1) >> 4; z++) for (int x = bounds.minX() >> 4; x <= (bounds.maxX() - 1) >> 4; x++) {
                ChunkCoordinates c = new ChunkCoordinates(x, z);
                int x1 = Math.max(bounds.minX(), x * 16), z1 = Math.max(bounds.minZ(), z * 16);
                int x2 = Math.min(bounds.maxX(), x * 16 + 16), z2 = Math.min(bounds.maxZ(), z * 16 + 16);
                patches.put(c, ChunkPatch.builder().setRegion(x1 - x * 16, bounds.minY(), z1 - z * 16, x2 - x * 16, bounds.maxY(), z2 - z * 16, "minecraft:air").build());
                // Entity relationship checks use the entire requested volume, not just one chunk's intersection.
                parts.put(c, bounds);
                checkBatch(patches.size());
            }
            return validatedPatches(world, patches, options, parts).thenCompose(EphemeralEdits::patchResults);
        } catch (Throwable error) { return CompletableFuture.failedFuture(error); }
    }

    private static final class Work {
        final World world;
        final ChunkCoordinates coordinate;
        final ChunkPatch patch;
        final Map<String, BlockData> palette;
        final PatchOptions options;
        final BlockBounds clear;
        final CompletableFuture<PatchChunkResult> future = new CompletableFuture<>();
        int regionIndex, removedEntities;
        long regionOffset, writes;
        Work(World world, ChunkCoordinates coordinate, ChunkPatch patch, Map<String, BlockData> palette, PatchOptions options, BlockBounds clear) {
            this.world = world; this.coordinate = coordinate; this.patch = patch; this.palette = palette; this.options = options; this.clear = clear;
        }
        CompletableFuture<PatchChunkResult> start() { dispatch(); return future; }
        void dispatch() {
            try {
                CompletableFuture<Boolean> dispatched = FoliaUtil.isCanvas()
                        ? CanvasEphemeralBackend.executeOnExistingRegion(world, coordinate.x(), coordinate.z(), this::step)
                        : EphemeralWorlds.onMain(() -> { step(); return true; });
                dispatched.whenComplete((ran, failure) -> {
                    if (failure != null) fail(failure);
                    else if (!ran) finish(PatchChunkResult.Status.NOT_LOADED);
                });
            } catch (Throwable failure) { fail(failure); }
        }
        void step() {
            if (future.isDone()) return;
            try {
                if (!world.isChunkLoaded(coordinate.x(), coordinate.z())) { finish(PatchChunkResult.Status.NOT_LOADED); return; }
                int tick = Bukkit.getCurrentTick();
                Budget local = BUDGETS.computeIfAbsent(world.getUID(), ignored -> new Budget(store(world).options().writesPerTick()));
                int budget = GLOBAL.take(tick, local.take(tick, options.writesPerTick()));
                if (budget == 0) { later(); return; }
                if (clear != null) {
                    List<Entity> entities = entitiesToRemove();
                    if (entities == null) { finish(PatchChunkResult.Status.BLOCKED); return; }
                    if (removedEntities + entities.size() > 4096) { finish(PatchChunkResult.Status.BLOCKED); return; }
                    for (Entity entity : entities) {
                        if (budget == 0) break;
                        entity.remove();
                        if (!((CraftEntity) entity).getHandle().isRemoved()) throw new IllegalStateException("Entity removal was refused");
                        removedEntities++; budget--;
                    }
                    if (entities.size() > 0 && budget == 0) { later(); return; }
                }
                while (budget > 0 && regionIndex < patch.regions().size()) {
                    var region = patch.regions().get(regionIndex);
                    int width = region.maxX() - region.minX(), length = region.maxZ() - region.minZ();
                    int x = coordinate.x() * 16 + region.minX() + (int) (regionOffset % width);
                    int y = region.minY() + (int) (regionOffset / (width * length));
                    int z = coordinate.z() * 16 + region.minZ() + (int) ((regionOffset / width) % length);
                    if (clear == null) world.getBlockAt(x, y, z).setBlockData(palette.get(region.state()), false);
                    else ChunkVolumeCleanup.clearBlock(level(world), x, y, z);
                    writes++; regionOffset++; budget--;
                    if (regionOffset == region.writes()) { regionIndex++; regionOffset = 0; }
                }
                if (regionIndex < patch.regions().size()) { later(); return; }
                if (clear != null) {
                    int x = coordinate.x() * 16, z = coordinate.z() * 16;
                    ChunkVolumeCleanup.clearTicks(level(world), new BlockBounds(Math.max(x, clear.minX()), clear.minY(), Math.max(z, clear.minZ()),
                            Math.min(x + 16, clear.maxX()), clear.maxY(), Math.min(z + 16, clear.maxZ())));
                }
                finish(PatchChunkResult.Status.APPLIED);
            } catch (Throwable failure) { fail(failure); }
        }
        List<Entity> entitiesToRemove() {
            var holder = level(world).moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(coordinate.x(), coordinate.z());
            var slices = holder == null ? null : holder.getEntityChunk();
            if (slices == null) return null; // Never load entity data implicitly or report cleanup before it arrives.
            var nativeEntities = slices.getAllEntities();
            if (nativeEntities.size() > 4096) return null;
            List<Entity> result = new ArrayList<>();
            for (var nativeEntity : nativeEntities) {
                Entity entity = nativeEntity.getBukkitEntity();
                Location location = entity.getLocation();
                if (!clear.contains(location.getX(), location.getY(), location.getZ())) continue;
                if (entity instanceof Player) return null;
                ArrayDeque<Entity> relatives = new ArrayDeque<>();
                Entity vehicle = entity.getVehicle(); if (vehicle != null) relatives.add(vehicle);
                relatives.addAll(entity.getPassengers());
                Set<UUID> visited = new HashSet<>(); visited.add(entity.getUniqueId());
                while (!relatives.isEmpty()) {
                    Entity relative = relatives.removeFirst();
                    if (!visited.add(relative.getUniqueId())) continue;
                    if (visited.size() > 4096 || relative instanceof Player || !Bukkit.isOwnedByCurrentRegion(relative)) return null;
                    Location pos = relative.getLocation();
                    if (!clear.contains(pos.getX(), pos.getY(), pos.getZ())) return null;
                    if (relative.getVehicle() != null) relatives.add(relative.getVehicle());
                    relatives.addAll(relative.getPassengers());
                }
                result.add(entity);
            }
            return result;
        }
        void later() {
            try { Bukkit.getGlobalRegionScheduler().runDelayed(PolarPaper.getPlugin(), ignored -> dispatch(), 1); }
            catch (Throwable failure) { fail(failure); }
        }
        void finish(PatchChunkResult.Status status) { future.complete(new PatchChunkResult(status, writes, removedEntities)); }
        void fail(Throwable error) { future.completeExceptionally(new PatchApplicationException(new PatchChunkResult(PatchChunkResult.Status.FAILED, writes, removedEntities), error)); }
    }
    private static final class Budget {
        final int limit;
        int tick = Integer.MIN_VALUE, remaining;
        Budget(int limit) { this.limit = limit; }
        synchronized int take(int currentTick, int requested) {
            if (tick != currentTick) { tick = currentTick; remaining = limit; }
            int granted = Math.min(remaining, requested); remaining -= granted; return granted;
        }
    }
    private record Preparation<T>(T value, String rejected) { }
    /** Bulk validation/partitioning is bounded and off tick threads; closing drains it before unloading. */
    private static <T> CompletableFuture<T> prepare(World world, long bytes, Callable<T> action) {
        return EphemeralWorlds.edit(world, PREPARATION_COORDINATE, bytes, () -> CompletableFuture.supplyAsync(() -> {
            try { return new Preparation<>(action.call(), null); }
            catch (Exception error) { throw new CompletionException(error); }
        }, PREPARATION), new Preparation<T>(null, "busy"), new Preparation<T>(null, "world destroying"), new Preparation<T>(null, "capacity exceeded"))
                .thenApply(result -> {
                    if (result.rejected() != null) throw new RejectedExecutionException("Preparation rejected: " + result.rejected());
                    return result.value();
                });
    }
    private static void checkBatch(int size) { if (size > 256) throw new IllegalArgumentException("Batch exceeds 256 chunks"); }
    private static <T> CompletableFuture<Map<ChunkCoordinates, T>> all(Map<ChunkCoordinates, CompletableFuture<T>> futures) {
        return CompletableFuture.allOf(futures.values().toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<ChunkCoordinates, T> results = new LinkedHashMap<>(); futures.forEach((c, future) -> results.put(c, future.join())); return Map.copyOf(results);
        });
    }
    private static CompletableFuture<PatchChunksResult> patchResults(Map<ChunkCoordinates, CompletableFuture<PatchChunkResult>> futures) {
        return CompletableFuture.allOf(futures.values().stream().map(f -> f.handle((v, e) -> null)).toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            Map<ChunkCoordinates, PatchChunkResult> results = new LinkedHashMap<>();
            Map<ChunkCoordinates, Throwable> errors = new LinkedHashMap<>();
            futures.forEach((c, future) -> {
                try { results.put(c, future.join()); }
                catch (CompletionException error) { errors.put(c, error.getCause()); }
            });
            if (!errors.isEmpty()) throw new PatchBatchException(results, errors);
            return new PatchChunksResult(results);
        });
    }
}
