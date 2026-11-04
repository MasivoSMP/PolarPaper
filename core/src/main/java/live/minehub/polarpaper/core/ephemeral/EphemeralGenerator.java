package live.minehub.polarpaper.core.ephemeral;

import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.core.world.PolarWorld;
import live.minehub.polarpaper.core.world.PolarWorldAccess;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/** Uses the ordinary server generation/unload lifecycle, never Polar's injected NoUnloadLevelChunk. */
public class EphemeralGenerator extends PolarGenerator {
    private byte[] userData = new byte[0];

    public EphemeralGenerator(Config config, PolarWorldAccess access) {
        super(config, null, access);
        if (!config.ephemeral()) throw new IllegalArgumentException("ephemeral must be enabled");
    }

    @Override public CompletableFuture<Void> load(World world) { return CompletableFuture.completedFuture(null); }
    @Override public PolarWorld getPolarWorld() { return null; }
    @Override public boolean isParallelCapable() { return true; }
    @Override public boolean shouldGenerateNoise() { return false; }
    @Override public boolean shouldGenerateSurface() { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves() { return false; }
    @Override public boolean shouldGenerateDecorations() { return false; }
    @Override public boolean shouldGenerateMobs() { return false; }
    @Override public boolean shouldGenerateStructures() { return false; }
    @Override public void addInfoComponent(World world, TextComponent.Builder builder) { builder.append(net.kyori.adventure.text.Component.text(" Ephemeral (RAM only)")); }
    @Override public byte[] getUserData() { return userData; }
    @Override public void setUserData(byte[] userData) { this.userData = userData; }

    public void release() {
        userData = new byte[0];
        // Config locations must not retain a detached Bukkit world.
        setConfig(getConfig().toBuilder().spawn(getConfig().spawn().clone()).build());
        getConfig().spawn().setWorld(null);
    }
}
