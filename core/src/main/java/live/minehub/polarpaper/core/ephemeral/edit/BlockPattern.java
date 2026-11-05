package live.minehub.polarpaper.core.ephemeral.edit;

import java.util.*;

/** Immutable, registry-independent block volume. Omitted blocks are air. No Bukkit calls are needed to build it. */
public final class BlockPattern {
    public static final int MAX_BLOCKS = 1_048_576;
    private final int width, height, length;
    private final List<String> palette;
    private final int[] blocks;

    private BlockPattern(Builder builder) {
        width = builder.width; height = builder.height; length = builder.length;
        palette = List.copyOf(builder.palette.keySet()); blocks = builder.blocks.clone();
    }
    public static Builder builder(int width, int height, int length) { return new Builder(width, height, length); }
    public int width() { return width; }
    public int height() { return height; }
    public int length() { return length; }
    public int volume() { return blocks.length; }
    public List<String> palette() { return palette; }
    public String block(int x, int y, int z) { return palette.get(blocks[index(x, y, z, width, height, length)]); }
    public long estimatedBytes() { return 64L + blocks.length * 4L + palette.stream().mapToLong(s -> 40L + s.length() * 2L).sum(); }
    public static boolean isAir(String state) { return state.equals("minecraft:air") || state.equals("minecraft:cave_air") || state.equals("minecraft:void_air"); }

    private static int index(int x, int y, int z, int w, int h, int l) {
        if (x < 0 || x >= w || y < 0 || y >= h || z < 0 || z >= l) throw new IndexOutOfBoundsException("Outside pattern");
        return x + z * w + y * w * l;
    }
    public static final class Builder {
        private final int width, height, length;
        private final LinkedHashMap<String, Integer> palette = new LinkedHashMap<>();
        private final int[] blocks;
        private int paletteCharacters = 13;
        private Builder(int width, int height, int length) {
            if (width <= 0 || height <= 0 || length <= 0 || (long) width * height > MAX_BLOCKS / (long) length)
                throw new IllegalArgumentException("Pattern must contain 1.." + MAX_BLOCKS + " blocks");
            this.width = width; this.height = height; this.length = length;
            blocks = new int[width * height * length]; palette.put("minecraft:air", 0);
        }
        public Builder setBlock(int x, int y, int z, String state) {
            Objects.requireNonNull(state);
            if (state.isBlank() || state.length() > 4096) throw new IllegalArgumentException("Invalid block state string");
            blocks[index(x, y, z, width, height, length)] = palette.computeIfAbsent(state, ignored -> {
                if (palette.size() >= 4096 || paletteCharacters + state.length() > 1_048_576) throw new IllegalArgumentException("Pattern palette exceeds limits");
                paletteCharacters += state.length(); return palette.size();
            });
            return this;
        }
        public Builder setRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String state) {
            index(minX, minY, minZ, width, height, length);
            index(maxX - 1, maxY - 1, maxZ - 1, width, height, length);
            if (minX >= maxX || minY >= maxY || minZ >= maxZ) throw new IllegalArgumentException("Empty region");
            for (int y = minY; y < maxY; y++) for (int z = minZ; z < maxZ; z++) for (int x = minX; x < maxX; x++) setBlock(x, y, z, state);
            return this;
        }
        public BlockPattern build() { return new BlockPattern(this); }
    }
}
