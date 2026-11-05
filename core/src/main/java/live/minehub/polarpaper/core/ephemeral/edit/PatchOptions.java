package live.minehub.polarpaper.core.ephemeral.edit;

public record PatchOptions(LoadPolicy loadPolicy, int writesPerTick) {
    public enum LoadPolicy { REQUIRE_LOADED, LOAD_EXISTING }
    public static final PatchOptions DEFAULT = new PatchOptions(LoadPolicy.REQUIRE_LOADED, 2048);
    public PatchOptions {
        java.util.Objects.requireNonNull(loadPolicy);
        if (writesPerTick < 1 || writesPerTick > 8192) throw new IllegalArgumentException("Invalid tick budget");
    }
}
