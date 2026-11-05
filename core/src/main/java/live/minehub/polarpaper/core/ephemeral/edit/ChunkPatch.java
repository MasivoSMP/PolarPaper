package live.minehub.polarpaper.core.ephemeral.edit;

import java.util.*;

/** Ordered writes in local chunk coordinates; omitted positions are unchanged. */
public final class ChunkPatch {
    public record Region(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, String state) {
        public Region {
            if (minX < 0 || minZ < 0 || maxX > 16 || maxZ > 16 || minX >= maxX || minY >= maxY || minZ >= maxZ)
                throw new IllegalArgumentException("Invalid local patch region");
            Objects.requireNonNull(state);
            if (state.isBlank() || state.length() > 4096) throw new IllegalArgumentException("Invalid state");
        }
        public long writes() { return (long) (maxX - minX) * ((long) maxY - minY) * (maxZ - minZ); }
    }
    private final List<Region> regions;
    private final long writes;
    private ChunkPatch(Builder builder) { regions = List.copyOf(builder.regions); writes = builder.writes; }
    public static Builder builder() { return new Builder(); }
    public List<Region> regions() { return regions; }
    public long writes() { return writes; }
    public long estimatedBytes() { return 32L + regions.stream().mapToLong(r -> 80L + r.state().length() * 2L).sum(); }
    public static final class Builder {
        private final List<Region> regions = new ArrayList<>();
        private long writes;
        private long stateCharacters;
        public Builder setBlock(int x, int y, int z, String state) { return setRegion(x, y, z, x + 1, Math.addExact(y, 1), z + 1, state); }
        public Builder setRegion(int x1, int y1, int z1, int x2, int y2, int z2, String state) {
            Region region = new Region(x1, y1, z1, x2, y2, z2, state);
            if (writes + region.writes() > BlockPattern.MAX_BLOCKS) throw new IllegalArgumentException("Patch exceeds write limit");
            if (regions.size() >= 65536 || stateCharacters + state.length() > 2_097_152) throw new IllegalArgumentException("Patch metadata exceeds limit");
            stateCharacters += state.length();
            writes += region.writes(); regions.add(region); return this;
        }
        public ChunkPatch build() { return new ChunkPatch(this); }
    }
}
