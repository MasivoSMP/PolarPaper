package live.minehub.polarpaper.core.ephemeral.edit;

public record BlockPosition(int x, int y, int z) {
    public BlockPosition add(int dx, int dy, int dz) {
        return new BlockPosition(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }
}
