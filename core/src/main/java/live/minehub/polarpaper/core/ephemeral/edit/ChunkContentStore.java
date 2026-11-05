package live.minehub.polarpaper.core.ephemeral.edit;

import live.minehub.polarpaper.core.ephemeral.ChunkCoordinates;
import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.*;

/** Generator-owned payloads. Native publication/discard checks must hold the holder creation locks. */
public final class ChunkContentStore {
    private static long globalBytes;
    private static int globalPublications;
    private static synchronized boolean reserve(long bytes, boolean publication) {
        if (bytes > 256L * 1024 * 1024 - globalBytes || (publication && globalPublications >= 4096)) return false;
        globalBytes += bytes; if (publication) globalPublications++; return true;
    }
    private static synchronized void unreserve(long bytes, boolean publication) {
        globalBytes -= bytes; if (publication) globalPublications--;
    }
    private final ChunkEditingOptions options;
    private final Map<ChunkCoordinates, Content> published = new HashMap<>();
    // Biome sources can query neighbors which will never become chunks. Never keep an unlimited generation ledger.
    private final LinkedHashMap<ChunkCoordinates, Content> providerCache = new LinkedHashMap<>(16, .75f, true);
    private ChunkContentProvider provider;
    private long pendingBytes, cacheBytes;
    private boolean closing;
    private volatile Throwable restorationFailure;
    public void failRestoration(Throwable error) { restorationFailure = error; }
    public void checkRestoration() {
        if (restorationFailure != null) throw new IllegalStateException("Block entity restoration failed; discard this world", restorationFailure);
    }
    /** Called synchronously by ChunkLoadEvent on its owning region, before publication is consumed. */
    public void restore(org.bukkit.World world, net.minecraft.world.level.chunk.LevelChunk chunk) {
        var level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
        ca.spottedleaf.moonrise.common.util.TickThread.ensureTickThread(level, chunk.locX, chunk.locZ, "Restore prepared block entities");
        try {
            checkRestoration();
            Content payload = content(world, chunk.locX, chunk.locZ);
            if (payload == null) return;
            // Validate the entire live target before applying the first NBT payload.
            for (PreparedBlockEntity entity : payload.chunk().blockEntities()) {
                var pos = new net.minecraft.core.BlockPos(chunk.locX * 16 + entity.x(), entity.y(), chunk.locZ * 16 + entity.z());
                var type = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(entity.type()));
                if (type == null || !type.isValid(chunk.getBlockState(pos))) throw new IllegalArgumentException("Block entity does not match target block at " + pos);
            }
            for (PreparedBlockEntity entity : payload.chunk().blockEntities()) {
                live.minehub.polarpaper.core.generator.ChunkUtils.addBlockEntity(entity.x(), entity.y(), entity.z(), entity.data(), chunk);
            }
        } catch (Throwable error) { restorationFailure = error; throw error; }
    }
    private static final ThreadLocal<Boolean> preparing = ThreadLocal.withInitial(() -> false);
    public static void checkNotPreparing() {
        if (preparing.get()) throw new IllegalStateException("Content providers must not call Polar lifecycle/editing APIs");
    }

    public ChunkContentStore(ChunkContentProvider provider, ChunkEditingOptions options) { this.provider = provider; this.options = Objects.requireNonNull(options); }
    public ChunkEditingOptions options() { return options; }

    /** Resolve only registry data, not live chunks. Also used on concurrent generator threads. */
    public Content resolve(PreparedChunk chunk, int minY, int maxY) {
        Objects.requireNonNull(chunk);
        if (chunk.minY() != minY || chunk.maxY() != maxY) throw new IllegalArgumentException("Payload heights must match destination world");
        Map<String, BlockData> blocks = new HashMap<>();
        for (String state : chunk.blocks().palette()) blocks.put(state, Bukkit.createBlockData(state));
        for (PreparedBlockEntity entity : chunk.blockEntities()) {
            var type = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(net.minecraft.resources.Identifier.parse(entity.type()));
            var state = ((org.bukkit.craftbukkit.block.data.CraftBlockData) blocks.get(chunk.blocks().block(entity.x(), entity.y() - minY, entity.z()))).getState();
            if (type == null || !type.isValid(state)) throw new IllegalArgumentException("Prepared block entity type does not match block");
        }
        Map<String, Biome> biomes = new HashMap<>();
        for (String key : chunk.biomeKeys()) {
            NamespacedKey name = NamespacedKey.fromString(key);
            Biome biome = name == null ? null : Registry.BIOME.get(name);
            if (biome == null) throw new IllegalArgumentException("Unknown biome: " + key);
            biomes.put(key, biome);
        }
        return new Content(chunk, Map.copyOf(blocks), Map.copyOf(biomes));
    }

    public synchronized PublishChunkResult publish(ChunkCoordinates coordinate, Content content) {
        if (closing) return PublishChunkResult.WORLD_DESTROYING;
        if (published.containsKey(coordinate)) return PublishChunkResult.ALREADY_PUBLISHED;
        if (published.size() >= options.maxPublications() || content.bytes() > options.maxPendingBytes() - pendingBytes)
            return PublishChunkResult.CAPACITY_EXCEEDED;
        while (!providerCache.isEmpty() && content.bytes() > options.maxPendingBytes() - pendingBytes - cacheBytes) {
            var iterator = providerCache.entrySet().iterator();
            Content evicted = iterator.next().getValue(); iterator.remove();
            cacheBytes -= evicted.bytes(); unreserve(evicted.bytes(), false);
        }
        if (!reserve(content.bytes(), true)) return PublishChunkResult.CAPACITY_EXCEEDED;
        published.put(coordinate, content); pendingBytes += content.bytes();
        Content cached = providerCache.remove(coordinate); if (cached != null) { cacheBytes -= cached.bytes(); unreserve(cached.bytes(), false); }
        return PublishChunkResult.PUBLISHED;
    }
    public synchronized boolean hasPublication(ChunkCoordinates coordinate) { return published.containsKey(coordinate); }
    public synchronized boolean discard(ChunkCoordinates coordinate) {
        Content removed = published.remove(coordinate);
        if (removed != null) { pendingBytes -= removed.bytes(); unreserve(removed.bytes(), true); }
        Content cached = providerCache.remove(coordinate); if (cached != null) { cacheBytes -= cached.bytes(); unreserve(cached.bytes(), false); }
        return removed != null;
    }
    public synchronized long pendingBytes() { return pendingBytes + cacheBytes; }
    public synchronized void startClosing() { closing = true; }
    public synchronized void release() {
        closing = true;
        restorationFailure = null;
        published.values().forEach(value -> unreserve(value.bytes(), true));
        providerCache.values().forEach(value -> unreserve(value.bytes(), false));
        published.clear(); providerCache.clear(); pendingBytes = cacheBytes = 0; provider = null; preparing.remove();
    }

    private Content content(WorldInfo info, int x, int z) {
        ChunkCoordinates coordinate = new ChunkCoordinates(x, z);
        ChunkContentProvider callback;
        synchronized (this) {
            Content result = published.get(coordinate);
            if (result == null) result = providerCache.get(coordinate);
            if (result != null) return result;
            callback = provider;
        }
        if (callback == null) return null;
        if (preparing.get()) throw new IllegalStateException("Recursive content provider");
        PreparedChunk prepared;
        preparing.set(true);
        try { prepared = callback.prepare(new ChunkGenerationContext(info.getName(), info.getSeed(), x, z, info.getMinHeight(), info.getMaxHeight())); }
        finally { preparing.remove(); }
        if (prepared == null) return null;
        Content result = resolve(prepared, info.getMinHeight(), info.getMaxHeight());
        synchronized (this) {
            Content explicit = published.get(coordinate);
            if (explicit != null) return explicit;
            // Provider must be deterministic. Bounded caching may recompute virtual biome queries.
            long limit = Math.min(Math.max(0, options.maxPendingBytes() - pendingBytes), Math.min(options.maxPendingBytes() / 4, 8L * 1024 * 1024));
            while (!providerCache.isEmpty() && (providerCache.size() >= 32 || result.bytes() > limit - cacheBytes)) {
                var iterator = providerCache.entrySet().iterator();
                Content evicted = iterator.next().getValue();
                cacheBytes -= evicted.bytes(); unreserve(evicted.bytes(), false); iterator.remove();
            }
            if (!closing && result.bytes() <= limit && reserve(result.bytes(), false)) {
                Content old = providerCache.put(coordinate, result);
                if (old != null) unreserve(old.bytes(), false);
                cacheBytes += result.bytes() - (old == null ? 0 : old.bytes());
            }
        }
        return result;
    }

    public Biome biome(WorldInfo info, int x, int y, int z) {
        Content content = content(info, x >> 4, z >> 4);
        if (content == null) return Biome.PLAINS;
        PreparedChunk chunk = content.chunk();
        return content.biomes().get(chunk.biome(x & 15, Math.clamp(y, chunk.minY(), chunk.maxY() - 1), z & 15));
    }
    public void generate(WorldInfo info, int x, int z, ChunkGenerator.ChunkData output) {
        Content content = content(info, x, z);
        if (content == null) return;
        BlockPattern pattern = content.chunk().blocks();
        for (int y = 0; y < pattern.height(); y++) for (int localZ = 0; localZ < 16; localZ++) {
            int start = 0;
            while (start < 16) {
                String state = pattern.block(start, y, localZ);
                int end = start + 1;
                while (end < 16 && pattern.block(end, y, localZ).equals(state)) end++;
                output.setRegion(start, content.chunk().minY() + y, localZ, end, content.chunk().minY() + y + 1, localZ + 1, content.blocks().get(state));
                start = end;
            }
        }
    }
    public record Content(PreparedChunk chunk, Map<String, BlockData> blocks, Map<String, Biome> biomes) {
        public long bytes() { return chunk.estimatedBytes() * 2; }
    }
}
