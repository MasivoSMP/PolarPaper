package live.minehub.polarpaper.core.ephemeral.edit;

/** Mirror local X first, then rotate clockwise about the volume; transformed coordinates remain nonnegative. */
public record PatternOptions(int quarterTurns, boolean mirrorX, boolean ignoreAir, PatchOptions patchOptions) {
    public static final PatternOptions DEFAULT = new PatternOptions(0, false, false, PatchOptions.DEFAULT);
    public PatternOptions { if (quarterTurns < 0 || quarterTurns > 3) throw new IllegalArgumentException("quarterTurns must be 0..3"); java.util.Objects.requireNonNull(patchOptions); }
    public BlockPosition transform(int x, int y, int z, int width, int length) {
        if (mirrorX) x = width - 1 - x;
        return switch (quarterTurns) {
            case 0 -> new BlockPosition(x, y, z);
            case 1 -> new BlockPosition(length - 1 - z, y, x);
            case 2 -> new BlockPosition(width - 1 - x, y, length - 1 - z);
            default -> new BlockPosition(z, y, width - 1 - x);
        };
    }
}
