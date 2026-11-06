package live.minehub.polarpaper.canvas_26_2;

import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.ephemeral.*;
import live.minehub.polarpaper.core.ephemeral.edit.*;
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

/** Loaded only on Pinac 26.2 with the native persistent-world API. */
public final class CanvasEphemeralBackend {
    private static final Set<UUID> REQUESTS = ConcurrentHashMap.newKeySet();
    private static Method destroy;
    private static Method persistent;
    private static boolean registered;
    private CanvasEphemeralBackend() {}

    public static synchronized void verifySupport() {
        if (destroy != null) return;
        try {
            persistent = WorldCreator.class.getMethod("persistent", boolean.class);
            destroy = ServerLevel.class.getMethod("canvas$destroyNonPersistentChunkAsync", int.class, int.class);
        } catch (ReflectiveOperationException error) {
            throw new UnsupportedOperationException("Canvas ephemeral worlds require Pinac 26.2 with persistent world support", error);
        }
    }

    public static World create(Plugin plugin, NamespacedKey key, Config config, PolarWorldAccess access) throws Exception {
        return create(plugin, key, config, access, null, ChunkEditingOptions.DEFAULT);
    }

    public static World create(Plugin plugin, NamespacedKey key, Config config, PolarWorldAccess access, ChunkContentProvider provider, ChunkEditingOptions options) throws Exception {
        verifySupport();
        if (!registered) {
            Bukkit.getPluginManager().registerEvents(new UnloadListener(), plugin);
            registered = true;
        }
        var server = ((CraftServer) Bukkit.getServer()).getServer();
        var dimension = CraftNamespacedKey.toResourceKey(Registries.DIMENSION, key);
        EphemeralStorage.reserve(plugin, server.storageSource.getDimensionPath(dimension), key.toString());
        var generator = new EphemeralGenerator(config, access, provider, options);
        WorldCreator creator = WorldCreator.ofKey(key).type(config.worldType()).environment(config.environment())
                .generator(generator).biomeProvider(generator.contentBiomeProvider());
        persistent.invoke(creator, false);
        World world = creator.createWorld();
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

    /** Dispatches to the owning region without a scheduler ticket or chunk load. */
    @SuppressWarnings("unchecked")
    public static CompletableFuture<DestroyChunkResult> destroyChunk(Plugin plugin, World world, int x, int z) {
        verifySupport();
        try {
            CompletableFuture<String> status = (CompletableFuture<String>) destroy.invoke(((CraftWorld) world).getHandle(), x, z);
            return status.thenApply(DestroyChunkResult::valueOf);
        } catch (java.lang.reflect.InvocationTargetException error) {
            return CompletableFuture.failedFuture(error.getCause());
        } catch (Throwable error) {
            return CompletableFuture.failedFuture(error);
        }
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
                if (status.isSuccess()) {
                    result.complete(null);
                } else if ("FAIL_RESOURCE_CLOSE".equals(status.name())) {
                    result.completeExceptionally(new IllegalStateException(
                            "Native shutdown failed; the server is shutting down and the world must not be retried or deleted"));
                } else {
                    result.completeExceptionally(new IllegalStateException("Canvas unload failed: " + status));
                }
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
}
