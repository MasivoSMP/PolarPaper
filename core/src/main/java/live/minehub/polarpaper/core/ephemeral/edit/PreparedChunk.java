package live.minehub.polarpaper.core.ephemeral.edit;

import java.util.*;

/** Complete chunk payload. Heights and biome cells must be aligned to four blocks. */
public final class PreparedChunk {
    private final int minY;
    private final BlockPattern blocks;
    private final String[] biomes;
    private final List<PreparedBlockEntity> blockEntities;
    private PreparedChunk(Builder builder) { minY = builder.minY; blocks = builder.blocks.build(); biomes = builder.biomes.clone(); blockEntities = List.copyOf(builder.blockEntities.values()); }
    public List<PreparedBlockEntity> blockEntities() { return blockEntities; }
    public static Builder builder(int minY, int maxY) { return new Builder(minY, maxY); }
    public int minY() { return minY; }
    public int maxY() { return minY + blocks.height(); }
    public BlockPattern blocks() { return blocks; }
    public String biome(int localX, int y, int localZ) {
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15 || y < minY || y >= maxY()) throw new IndexOutOfBoundsException();
        return biomes[(localX >> 2) + (localZ >> 2) * 4 + ((y - minY) >> 2) * 16];
    }
    public Set<String> biomeKeys() { return Set.copyOf(Arrays.asList(biomes)); }
    public long estimatedBytes() { return blocks.estimatedBytes() + biomes.length * 8L + biomeKeys().stream().mapToLong(s -> 40L + s.length() * 2L).sum() + blockEntities.stream().mapToLong(e -> 128L + e.encodedBytes() * 4L).sum(); }
    public static final class Builder {
        private final int minY, maxY;
        private final BlockPattern.Builder blocks;
        private final String[] biomes;
        private final Map<BlockPosition, PreparedBlockEntity> blockEntities = new LinkedHashMap<>();
        private long nbtBytes;
        public Builder blockEntity(PreparedBlockEntity entity) {
            Objects.requireNonNull(entity);
            if (entity.y() < minY || entity.y() >= maxY) throw new IllegalArgumentException("Block entity Y outside payload");
            var key = new BlockPosition(entity.x(), entity.y(), entity.z());
            if (blockEntities.containsKey(key)) throw new IllegalArgumentException("Duplicate block entity position");
            if (blockEntities.size() >= 4096 || nbtBytes + entity.encodedBytes() > 4L * 1024 * 1024) throw new IllegalArgumentException("Chunk block entity budget exceeded");
            blockEntities.put(key, entity); nbtBytes += entity.encodedBytes(); return this;
        }
        private Builder(int minY, int maxY) {
            if (minY % 4 != 0 || maxY % 4 != 0 || minY >= maxY || (long) maxY - minY > 4096) throw new IllegalArgumentException("Invalid chunk heights");
            this.minY = minY; this.maxY = maxY; blocks = BlockPattern.builder(16, maxY - minY, 16);
            biomes = new String[(maxY - minY) * 4]; Arrays.fill(biomes, "minecraft:plains");
        }
        public Builder setBlock(int x, int y, int z, String state) { blocks.setBlock(x, y - minY, z, state); return this; }
        public Builder setRegion(int x1, int y1, int z1, int x2, int y2, int z2, String state) { blocks.setRegion(x1, y1 - minY, z1, x2, y2 - minY, z2, state); return this; }
        public Builder fillBiome(String key) { validateKey(key); Arrays.fill(biomes, key); return this; }
        public Builder setBiome(int x, int y, int z, String key) {
            validateKey(key);
            if (x < 0 || x > 15 || z < 0 || z > 15 || y < minY || y >= maxY) throw new IndexOutOfBoundsException();
            biomes[(x >> 2) + (z >> 2) * 4 + ((y - minY) >> 2) * 16] = key; return this;
        }
        private static void validateKey(String key) { if (key == null || key.isBlank() || key.length() > 256) throw new IllegalArgumentException("Invalid biome key"); }
        public PreparedChunk build() { return new PreparedChunk(this); }
    }
}
