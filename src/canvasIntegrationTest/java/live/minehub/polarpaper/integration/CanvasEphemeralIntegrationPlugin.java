package live.minehub.polarpaper.integration;

import live.minehub.polarpaper.Polar;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.ephemeral.*;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.core.source.BytesPolarSource;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.ref.WeakReference;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable patched-Canvas test only. Creates worlds and shuts the server down. */
public final class CanvasEphemeralIntegrationPlugin extends JavaPlugin {
    private final List<WeakReference<Object>> chunks = new CopyOnWriteArrayList<>();
    private final List<WeakReference<Object>> worlds = new CopyOnWriteArrayList<>();
    private final Set<Long> owners = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelUnload;
    private int assertions;

    @Override public void onEnable() {
        try {
            @SuppressWarnings("unchecked") Class<? extends Event> event = (Class<? extends Event>) Class.forName("io.canvasmc.canvas.event.world.WorldUnloadAsyncEvent");
            getServer().getPluginManager().registerEvent(event, new Listener() {}, EventPriority.NORMAL, (listener, value) -> {
                if (cancelUnload) ((Cancellable) value).setCancelled(true);
            }, this);
        } catch (Exception failure) { finish(failure); return; }
        Config config = Config.Builder.defaults().ephemeral(true).loadOnStartup(false).build();
        Polar.createEphemeralWorld("canvas_ephemeral", config).thenCompose(this::exercise)
            .thenCompose(ignored -> Polar.createEphemeralWorld("canvas_ephemeral", config))
            .thenCompose(world -> {
                var pending = Polar.destroyChunk(world, 128, 128);
                var closing = Polar.destroyWorld(world);
                check(Polar.destroyChunk(world, 512, 512).join() == DestroyChunkResult.WORLD_DESTROYING, "closing blocks new chunk requests");
                return closing.thenRun(() -> check(pending.isDone(), "world destruction drains region operations"));
             }).thenCompose(ignored -> collect(worlds, 0, "detached Canvas world and level collected"))
             .thenCompose(ignored -> EditingIntegration.run(this).thenAccept(count -> assertions += count))
            .whenComplete((ignored, failure) -> finish(failure));
    }

    private CompletableFuture<Void> exercise(World world) {
        Path folder = world.getWorldFolder().toPath();
        worlds.add(new WeakReference<>(world));
        worlds.add(new WeakReference<>(((CraftWorld) world).getHandle()));
        check(PolarGenerator.fromWorld(world).getSource() == null, "no source");
        check(PolarGenerator.fromWorld(world).getPolarWorld() == null, "no retained PolarWorld");
        check(Polar.saveWorld(world).isCompletedExceptionally(), "implicit save rejected");
        check(Polar.saveWorld(world, new BytesPolarSource()).isCompletedExceptionally(), "explicit save rejected");
        var absent = Polar.destroyChunk(world, 20000, -20000);
        check(absent == Polar.destroyChunk(world, 20000, -20000), "duplicate operation coalesced");
        return absent.thenCompose(result -> {
            check(result == DestroyChunkResult.ALREADY_ABSENT, "absent coordinate not generated");
            return global(() -> {
                CompletableFuture<Void> rejected = new CompletableFuture<>();
                java.util.function.Consumer<Object> callback = status -> {
                    try { check(status.toString().equals("FAIL_UNLOAD_EVENT"), "external unchecked async unload rejected"); rejected.complete(null); }
                    catch (Throwable failure) { rejected.completeExceptionally(failure); }
                };
                Bukkit.getServer().getClass().getMethod("unloadWorldAsync", World.class, boolean.class, java.util.function.Consumer.class)
                    .invoke(Bukkit.getServer(), world, false, callback);
                return rejected;
            }).thenCompose(future -> future);
        }).thenCompose(ignored -> {
            return CompletableFuture.allOf(setup(world, 128), setup(world, 512));
        }).thenCompose(ignored -> Polar.destroyChunks(world, List.of(new ChunkCoordinates(128, 128), new ChunkCoordinates(20000, -20000))))
          .thenCompose(batch -> {
            check(batch.results().get(new ChunkCoordinates(128, 128)) == DestroyChunkResult.BLOCKED, "foreign plugin ticket blocks unload");
            check(batch.results().get(new ChunkCoordinates(20000, -20000)) == DestroyChunkResult.ALREADY_ABSENT, "partial batch preserves absent outcome");
            return CompletableFuture.allOf(region(world, 128, () -> { world.removePluginChunkTicket(128, 128, this); return null; }),
                region(world, 512, () -> { world.removePluginChunkTicket(512, 512, this); return null; }));
          }).thenCompose(ignored -> delay(120))
          .thenCompose(ignored -> {
            check(owners.size() >= 1, "region-owned setup completed");
            var first = Polar.destroyChunk(world, 128, 128);
            var second = Polar.destroyChunk(world, 512, 512);
            return first.thenCombine(second, (a, b) -> {
                check(a == DestroyChunkResult.DESTROYED && b == DestroyChunkResult.DESTROYED, "distant regional chunks destroyed without scheduler tickets");
                return null;
            });
          }).thenCompose(ignored -> global(() -> {
            var manager = ((CraftWorld) world).getHandle().moonrise$getChunkTaskScheduler().chunkHolderManager;
            check(manager.getChunkHolder(128, 128) == null && manager.getChunkHolder(512, 512) == null, "native holders removed");
            check(!world.isChunkLoaded(20000, -20000), "absent operation never loaded chunk");
            return null;
          })).thenCompose(ignored -> world.getChunkAtAsync(128, 128))
          .thenCompose(ignored -> region(world, 128, () -> {
            check(world.getBlockAt(2048, 64, 2048).getType().isAir(), "revisited floor is void");
            check(world.getNearbyEntities(new Location(world, 2048.5, 65, 2048.5), 3, 3, 3).isEmpty(), "old entities removed");
            return null;
          })).thenCompose(ignored -> collect(chunks, 0, "destroyed holders/chunks/entities collected while world remains loaded"))
          .thenCompose(ignored -> global(() -> {
            check(Bukkit.getWorld(world.getUID()) == world, "chunk GC does not require world destruction");
            try (var files = Files.walk(folder)) {
                check(files.noneMatch(path -> path.toString().endsWith(".mca") || path.toString().endsWith(".polar")), "no world content persisted");
            }
            cancelUnload = true;
            return null;
          })).thenCompose(ignored -> Polar.destroyWorld(world).handle((value, failure) -> {
            check(failure != null && Files.exists(folder), "cancelled native unload preserves owned folder");
            cancelUnload = false;
            return null;
          })).thenCompose(ignored -> global(() -> {
            Files.move(folder.resolve(".polar-ephemeral"), folder.resolve("ownership-backup"));
            return null;
          })).thenCompose(ignored -> Polar.destroyWorld(world).handle((value, failure) -> {
            check(failure != null && Files.exists(folder), "cleanup failure reported after checked unload");
            try { Files.move(folder.resolve("ownership-backup"), folder.resolve(".polar-ephemeral")); }
            catch (Exception error) { throw new CompletionException(error); }
            return null;
          })).thenCompose(ignored -> Polar.destroyWorld(world))
          .thenRun(() -> check(!Files.exists(folder), "detached cleanup retry removes owned directory"));
    }

    private CompletableFuture<Void> setup(World world, int coordinate) {
        return world.getChunkAtAsync(coordinate, coordinate).thenCompose(ignored -> region(world, coordinate, () -> {
            check(Bukkit.isOwnedByCurrentRegion(world, coordinate, coordinate), "setup owns target region");
            owners.add(Thread.currentThread().threadId());
            world.addPluginChunkTicket(coordinate, coordinate, this);
            int block = coordinate << 4;
            check(world.getBlockAt(block, 64, block).getType().isAir(), "initial floor is void");
            world.getBlockAt(block, 64, block).setType(Material.CHEST);
            world.getBlockAt(block + 1, 64, block).setType(Material.COMPOSTER);
            ArmorStand stand = world.spawn(new Location(world, block + .5, 65, block + .5), ArmorStand.class);
            stand.setGravity(false);
            var holder = ((CraftWorld) world).getHandle().moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(coordinate, coordinate);
            chunks.add(new WeakReference<>(holder));
            chunks.add(new WeakReference<>(holder.getCurrentChunk()));
            chunks.add(new WeakReference<>(stand));
            return null;
        }));
    }

    private CompletableFuture<Void> collect(List<WeakReference<Object>> references, int attempt, String message) {
        System.gc();
        return delay(20).thenCompose(ignored -> {
            if (references.stream().allMatch(reference -> reference.get() == null)) {
                check(true, message);
                return CompletableFuture.completedFuture(null);
            }
            if (attempt == 9) return CompletableFuture.failedFuture(new AssertionError("Native references remain"));
            return collect(references, attempt + 1, message);
        });
    }

    private <T> CompletableFuture<T> region(World world, int coordinate, Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(this, world, coordinate, coordinate, () -> complete(result, action));
        return result;
    }
    private <T> CompletableFuture<T> global(Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().execute(this, () -> complete(result, action));
        return result;
    }
    private <T> void complete(CompletableFuture<T> result, Callable<T> action) {
        try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); }
    }
    private CompletableFuture<Void> delay(long ticks) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Bukkit.getGlobalRegionScheduler().runDelayed(this, task -> result.complete(null), ticks);
        return result;
    }
    private synchronized void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        assertions++;
        getLogger().info("ASSERT " + message);
    }
    private void finish(Throwable failure) {
        String result = failure == null ? "PASS assertions=" + assertions : "FAIL " + failure;
        if (failure != null) failure.printStackTrace();
        getLogger().info(result);
        try { Files.writeString(Path.of("canvas-integration-result.txt"), result); }
        catch (Exception error) { error.printStackTrace(); }
        Bukkit.getGlobalRegionScheduler().execute(this, Bukkit::shutdown);
    }
}
