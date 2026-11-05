package live.minehub.polarpaper.integration;

import live.minehub.polarpaper.Polar;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.ephemeral.*;
import live.minehub.polarpaper.core.generator.PolarGenerator;
import live.minehub.polarpaper.core.source.BytesPolarSource;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.world.PolarWriter;
import live.minehub.polarpaper.nms.VersionUtil;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Integration assertions run on Paper's main thread. This plugin stops the disposable server on completion. */
public final class EphemeralIntegrationPlugin extends JavaPlugin implements Listener {
    private final List<WeakReference<Object>> released = new ArrayList<>();
    private final List<WeakReference<Object>> destroyedChunks = new ArrayList<>();
    private boolean cancelUnload;
    private UUID testWorld;
    private int assertions;

    @Override public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        after(20, () -> {
            Config config = Config.Builder.defaults().ephemeral(true).loadOnStartup(false).build();
            check(Polar.createWorld(new BytesPolarSource(), "integration_rejected", config).isCompletedExceptionally(), "source rejected");
            return Polar.createEphemeralWorld("integration_ephemeral", config)
                    .thenCompose(this::exerciseWorld)
                    .thenCompose(ignored -> Polar.createEphemeralWorld("integration_ephemeral", config))
                    .thenCompose(world -> {
                        check(world.getBlockAt(2048, 64, 2048).getType().isAir(), "same-name recreation is void");
                        var pending = Polar.destroyChunk(world, 128, 128);
                        return Polar.destroyWorld(world).thenRun(() -> check(pending.isDone(), "world destruction drains in-flight chunk operation"));
                    })
                    .thenCompose(ignored -> normalWorldRegression())
                    .thenCompose(ignored -> collectReleased(released, 0, "old world, level, chunk, holder and entity weak references cleared"))
                    .thenCompose(ignored -> EditingIntegration.run(this).thenAccept(count -> assertions += count));
        }).whenComplete((ignored, error) -> {
            if (error == null) {
                getLogger().info("EPHEMERAL_INTEGRATION_PASS assertions=" + assertions);
                try { Files.writeString(getDataFolder().toPath().toAbsolutePath().getParent().getParent().resolve("integration-result.txt"), "PASS assertions=" + assertions); }
                catch (Exception failure) { getLogger().log(java.util.logging.Level.SEVERE, "Cannot write test result", failure); }
            } else {
                getLogger().log(java.util.logging.Level.SEVERE, "EPHEMERAL_INTEGRATION_FAIL", error);
            }
            Bukkit.shutdown();
        });
    }

    @EventHandler public void cancel(WorldUnloadEvent event) {
        if (cancelUnload && event.getWorld().getUID().equals(testWorld)) event.setCancelled(true);
    }

    private CompletableFuture<Void> exerciseWorld(World world) {
        testWorld = world.getUID();
        Path folder = world.getWorldFolder().toPath();
        released.add(new WeakReference<>(world));
        released.add(new WeakReference<>(((CraftWorld) world).getHandle()));
        check(PolarGenerator.fromWorld(world).getPolarWorld() == null, "no retained PolarWorld");
        check(PolarGenerator.fromWorld(world).getSource() == null, "no source");
        check(Polar.saveWorld(world).isCompletedExceptionally(), "save without source rejected");
        check(Polar.saveWorld(world, new BytesPolarSource()).isCompletedExceptionally(), "explicit save rejected");
        check(PolarWriter.convertAndWrite(new BytesPolarSource(), world, PolarGenerator.fromWorld(world).getWorldAccess()).isCompletedExceptionally(), "export with persistent config rejected");
        check(!Bukkit.unloadWorld(world, false), "unchecked Bukkit unload rejected");
        return Polar.createEphemeralWorld("integration_ephemeral", PolarGenerator.fromWorld(world).getConfig())
                .handle((value, error) -> { check(error != null, "duplicate creation rejected"); return null; })
                .thenCompose(ignored -> {
                    var abandoned = Polar.destroyChunk(world, 6001, 6001);
                    check(abandoned.cancel(false), "caller can cancel returned future");
                    check(abandoned == Polar.destroyChunk(world, 6001, 6001), "cancelled result does not release in-flight coordination");
                    return after(2, () -> {
                        var fresh = Polar.destroyChunk(world, 6001, 6001);
                        check(fresh != abandoned, "native completion clears cancelled operation");
                        return fresh.thenAccept(result -> check(result == DestroyChunkResult.ALREADY_ABSENT, "cancelled absent operation did not load chunk"));
                    });
                })
                .thenCompose(ignored -> chunkLifecycle(world, folder));
    }

    private CompletableFuture<Void> chunkLifecycle(World world, Path folder) {
        setupChunk(world);
        CompletableFuture<DestroyChunkResult> first = Polar.destroyChunk(world, 128, 128);
        check(first == Polar.destroyChunk(world, 128, 128), "duplicate chunk requests coalesced");
        return first.thenCompose(result -> {
            check(result == DestroyChunkResult.BLOCKED, "plugin ticket blocks destruction");
            return Polar.destroyChunks(world, List.of(new ChunkCoordinates(128, 128), new ChunkCoordinates(6000, -6000), new ChunkCoordinates(128, 128)));
        }).thenCompose(result -> {
            check(result.results().size() == 2, "batch deduplicated");
            check(result.results().get(new ChunkCoordinates(128, 128)) == DestroyChunkResult.BLOCKED, "partial batch blocked result");
            check(result.results().get(new ChunkCoordinates(6000, -6000)) == DestroyChunkResult.ALREADY_ABSENT, "absent chunk not loaded");
            world.removePluginChunkTicket(128, 128, this);
            return after(120, () -> {
                var holder = ((CraftWorld) world).getHandle().moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(128, 128);
                check(holder != null && holder.getCurrentChunk() != null, "idle content retained until explicit destruction");
                released.add(new WeakReference<>(holder));
                released.add(new WeakReference<>(holder.getCurrentChunk()));
                destroyedChunks.add(new WeakReference<>(holder));
                destroyedChunks.add(new WeakReference<>(holder.getCurrentChunk()));
                ArmorStand entity = (ArmorStand) holder.getEntityChunk().getAllEntities().stream()
                        .filter(candidate -> !candidate.getPassengers().isEmpty()).findFirst().orElseThrow().getBukkitEntity();
                return Polar.destroyChunk(world, 128, 128).thenCompose(outcome -> {
                    check(outcome == DestroyChunkResult.BLOCKED, "non-unloadable entity blocks native destruction");
                    entity.eject();
                    return Polar.destroyChunk(world, 128, 128);
                });
            });
        }).thenCompose(result -> {
            check(result == DestroyChunkResult.DESTROYED, "ticket-free chunk destroyed");
            var holder = ((CraftWorld) world).getHandle().moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(128, 128);
            check(holder == null, "native holder removed");
            check(world.getBlockAt(2048, 64, 2048).getType().isAir(), "revisited block is void");
            check(world.getNearbyEntities(new Location(world, 2048.5, 65, 2048.5), 3, 3, 3).isEmpty(), "entities removed");
            try (var paths = Files.walk(folder)) {
                check(paths.noneMatch(path -> path.toString().endsWith(".mca") || path.toString().endsWith(".polar")), "no chunk/entity/POI/Polar files");
            } catch (Exception error) { throw new CompletionException(error); }
            return collectReleased(destroyedChunks, 0, "destroyed chunk, holder and entity collected while world stays loaded");
        }).thenCompose(ignored -> {
            check(Bukkit.getWorld(testWorld) == world, "chunk collection does not require world destruction");
            cancelUnload = true;
            return Polar.destroyWorld(world).handle((value, error) -> {
                check(error != null, "cancelled world unload fails");
                check(Bukkit.getWorld(testWorld) == world && Files.exists(folder), "cancelled unload preserves active folder");
                return null;
            });
        }).thenCompose(ignored -> {
            cancelUnload = false;
            try {
                Files.move(folder.resolve(".polar-ephemeral"), folder.resolve("ownership-backup"));
            } catch (Exception error) { throw new CompletionException(error); }
            CompletableFuture<Void> destroy = Polar.destroyWorld(world);
            check(destroy == Polar.destroyWorld(world), "duplicate world requests coalesced");
            check(Polar.destroyChunk(world, 128, 128).join() == DestroyChunkResult.WORLD_DESTROYING, "world closure blocks new chunk requests");
            return destroy.handle((value, error) -> {
                check(error != null && Bukkit.getWorld(testWorld) == null && Files.exists(folder), "failed owned-folder deletion is reported after unload");
                try { Files.move(folder.resolve("ownership-backup"), folder.resolve(".polar-ephemeral")); }
                catch (Exception failure) { throw new CompletionException(failure); }
                return null;
            });
        }).thenCompose(ignored -> Polar.destroyWorld(world)).thenAccept(ignored -> {
            check(!Files.exists(folder), "cleanup retry deletes detached owned folder");
            check(Bukkit.getWorld(testWorld) == null, "world deregistered");
        });
    }

    private void setupChunk(World world) {
        world.addPluginChunkTicket(128, 128, this);
        var chunk = world.getChunkAt(128, 128);
        check(chunk.getBlock(0, 64, 0).getType().isAir(), "initial world is void");
        chunk.getBlock(0, 63, 0).setType(Material.STONE);
        chunk.getBlock(0, 64, 0).setType(Material.CHEST);
        ((Chest) chunk.getBlock(0, 64, 0).getState()).getBlockInventory().setItem(0, new ItemStack(Material.DIAMOND));
        ArmorStand entity = world.spawn(new Location(world, 2048.5, 65, 2048.5), ArmorStand.class);
        entity.setGravity(false);
        ArmorStand passenger = world.spawn(new Location(world, 2048.5, 65, 2048.5), ArmorStand.class);
        passenger.setGravity(false);
        check(entity.addPassenger(passenger), "passenger attached for native-unload blocker test");
        released.add(new WeakReference<>(entity));
        destroyedChunks.add(new WeakReference<>(entity));
        released.add(new WeakReference<>(passenger));
        destroyedChunks.add(new WeakReference<>(passenger));
    }

    private CompletableFuture<Void> normalWorldRegression() {
        Config config = Config.Builder.defaults().loadOnStartup(false).build();
        return Polar.createWorld((PolarSource) null, "integration_persistent", config).thenCompose(world -> after(1, () -> {
            check(world != null, "normal world created");
            world.getBlockAt(0, 64, 0).setType(Material.DIAMOND_BLOCK);
            var source = new BytesPolarSource();
            return Polar.saveWorld(world, source).thenCompose(ignored -> {
                check(source.bytes().length > 0, "normal world still saves");
                return VersionUtil.getWorldUnloader().unloadWorld(this, world);
            }).thenAccept(success -> check(success, "normal world still unloads"));
        }));
    }

    private CompletableFuture<Void> collectReleased(List<WeakReference<Object>> references, int attempt, String message) {
        System.gc();
        return after(20, () -> {
            long remaining = references.stream().filter(reference -> reference.get() != null).count();
            if (remaining == 0) {
                check(true, message);
                return CompletableFuture.completedFuture(null);
            }
            if (attempt == 9) throw new AssertionError("Retained lifecycle objects: " + remaining);
            return collectReleased(references, attempt + 1, message);
        });
    }

    private <T> CompletableFuture<T> after(long ticks, Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try { action.get().whenComplete((value, error) -> { if (error == null) result.complete(value); else result.completeExceptionally(error); }); }
            catch (Throwable error) { result.completeExceptionally(error); }
        }, ticks);
        return result;
    }

    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        assertions++;
        getLogger().info("PASS: " + message);
    }
}
