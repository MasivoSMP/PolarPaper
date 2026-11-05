package live.minehub.polarpaper.core.ephemeral.edit;

/** Integer bounds: minimum inclusive, maximum exclusive. */
public record BlockBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public BlockBounds {
        if (minX >= maxX || minY >= maxY || minZ >= maxZ) throw new IllegalArgumentException("Empty/inverted bounds");
        if (minX < -30_000_000 || minZ < -30_000_000 || maxX > 30_000_000 || maxZ > 30_000_000)
            throw new IllegalArgumentException("Bounds exceed Minecraft horizontal limits");
    }
    public boolean contains(double x, double y, double z) {
        return x >= minX && x < maxX && y >= minY && y < maxY && z >= minZ && z < maxZ;
    }
    public long volume() { return Math.multiplyExact(Math.multiplyExact((long) maxX - minX, (long) maxY - minY), (long) maxZ - minZ); }
}
