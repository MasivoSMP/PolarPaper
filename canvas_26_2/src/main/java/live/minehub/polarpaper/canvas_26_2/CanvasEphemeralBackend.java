package live.minehub.polarpaper.canvas_26_2;

import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.ephemeral.*;
import live.minehub.polarpaper.core.world.PolarWorldAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Loaded only on Canvas 26.2 with the versioned native no-persistence/lifecycle patch. */
public final class CanvasEphemeralBackend {
    private static final Set<UUID> REQUESTS = ConcurrentHashMap.newKeySet();
    private static Method destroy;
    private static boolean registered;
    private CanvasEphemeralBackend() {}

    public static synchronized void verifySupport() {
        if (destroy != null) return;
        try {
            Class<?> bridge = Class.forName("io.canvasmc.canvas.world.EphemeralChunkLifecycle");
            if (!bridge.getMethod("contractVersion").invoke(null).equals(1)) throw new IllegalStateException("Incompatible native contract");
            Class.forName("io.canvasmc.canvas.world.EphemeralWorldGenerator");
            destroy = bridge.getMethod("destroyChunk", ServerLevel.class, int.class, int.class);
        } catch (ReflectiveOperationException error) {
            throw new UnsupportedOperationException("Canvas ephemeral worlds require server-patches/canvas-26.2-ephemeral.patch", error);
        }
    }

    public static World create(Plugin plugin, NamespacedKey key, Config config, PolarWorldAccess access) throws Exception {
        verifySupport();
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(new UnloadListener(), plugin);
            registered = true;
        }
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        var dimension = CraftNamespacedKey.toResourceKey(Registries.DIMENSION, key);
        EphemeralStorage.reserve(plugin, server.storageSource.getDimensionPath(dimension), key.toString());
        World world = WorldCreator.ofKey(key).type(config.worldType()).environment(config.environment())
                .generator(new CanvasGenerator(config, access)).createWorld();
        if (world == null) throw new IllegalStateException("Canvas did not create " + key);
        world.setAutoSave(false);
        world.setDifficulty(config.difficulty());
        world.setTime(config.time());
        var spawn = config.spawn();
        world.setSpawnLocation(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ(), spawn.getYaw());
        config.gamerules().forEach((name, value) -> {
            var ruleKey = NamespacedKey.fromString(name);
            if (ruleKey == null) return;
            @SuppressWarnings("unchecked") GameRule<Object> rule = (GameRule<Object>) Registry.GAME_RULE.get(ruleKey);
            if (rule != null) world.setGameRule(rule, value);
        });
        return world;
    }

    public static CompletableFuture<DestroyChunkResult> destroyChunk(World world, int x, int z) {
        try {
            verifySupport();
            @SuppressWarnings("unchecked") var future = (CompletableFuture<String>) destroy.invoke(null, ((CraftWorld) world).getHandle(), x, z);
            return future.thenApply(DestroyChunkResult::valueOf);
        } catch (ReflectiveOperationException error) { return CompletableFuture.failedFuture(error); }
    }

    /** Must be requested on the global region. Success is emitted only after checked native closure. */
    public static CompletableFuture<Void> unloadWorld(World world) {
        if (((CraftWorld) world).getHandle().canvas$unloadTicket.isPresent()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Native unload already started; do not retry or delete its directory"));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        REQUESTS.add(world.getUID());
        try {
            Bukkit.getServer().unloadWorldAsync(world, false, status -> {
                if (status.isSuccess()) result.complete(null);
                else result.completeExceptionally(new IllegalStateException("Canvas unload failed: " + status));
            });
        } catch (Throwable error) { result.completeExceptionally(error); }
        finally { REQUESTS.remove(world.getUID()); }
        return result;
    }

    public static final class UnloadListener implements Listener {
        @EventHandler(priority = EventPriority.HIGHEST)
        public void onUnload(io.canvasmc.canvas.event.world.WorldUnloadAsyncEvent event) {
            if (event.getWorld().getGenerator() instanceof EphemeralGenerator && !REQUESTS.remove(event.getWorld().getUID())) {
                event.setCancelled(true);
            }
        }
    }

    private static final class CanvasGenerator extends EphemeralGenerator implements io.canvasmc.canvas.world.EphemeralWorldGenerator {
        CanvasGenerator(Config config, PolarWorldAccess access) { super(config, access); }
    }
}
