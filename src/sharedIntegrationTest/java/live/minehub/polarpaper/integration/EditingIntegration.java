package live.minehub.polarpaper.integration;

import live.minehub.polarpaper.Polar;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.ephemeral.*;
import live.minehub.polarpaper.core.ephemeral.edit.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import org.bukkit.*;
import org.bukkit.block.Chest;
import org.bukkit.block.data.Directional;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Shared real-server assertions. RegionScheduler is only used by this fixture on explicitly generated chunks. */
final class EditingIntegration {
    private static final int A = 512, B = 513, Z = 512, X = A * 16, BLOCK_Z = Z * 16;
    private final JavaPlugin plugin;
    private int assertions;
    private WeakReference<PreparedChunk> payload;
    private UUID inside, outside;
    private EditingIntegration(JavaPlugin plugin) { this.plugin = plugin; }
    static CompletableFuture<Integer> run(JavaPlugin plugin) { return new EditingIntegration(plugin).run(); }

    private CompletableFuture<Integer> run() {
        var config = Config.Builder.defaults().ephemeral(true).loadOnStartup(false).build();
        ChunkContentProvider provider = context -> {
            if ((context.chunkX() != 1024 && context.chunkX() != 1100) || context.chunkZ() != 1024) return null;
            return PreparedChunk.builder(context.minY(), context.maxY()).setBlock(0, 64, 0, "minecraft:iron_block").fillBiome("minecraft:forest").build();
        };
        return Polar.createEphemeralWorld("integration_edits", config, provider, new ChunkEditingOptions(2, 8L * 1024 * 1024, 16, 64))
                .thenCompose(world -> publications(world).thenCompose(ignored -> patches(world))
                    .thenCompose(ignored -> patterns(world)).thenCompose(ignored -> cleanup(world))
                    .thenCompose(ignored -> providerAndRaces(world)).thenCompose(ignored -> collectPayload(0))
                    .thenCompose(ignored -> closing(world)))
                .thenApply(ignored -> { plugin.getLogger().info("EDITING_INTEGRATION_PASS assertions=" + assertions); return assertions; });
    }
    private CompletableFuture<Void> publications(World world) {
        PreparedChunk first = PreparedChunk.builder(world.getMinHeight(), world.getMaxHeight())
                .setRegion(0, 64, 0, 16, 65, 16, "minecraft:stone").setBlock(1, 65, 1, "minecraft:chest[facing=north]")
                .setBlock(2, 65, 2, "minecraft:composter").fillBiome("minecraft:desert").build();
        payload = new WeakReference<>(first);
        var second = PreparedChunk.builder(world.getMinHeight(), world.getMaxHeight()).setBlock(0, 64, 0, "minecraft:gold_block").build();
        return Polar.publishChunks(world, Map.of(new ChunkCoordinates(A, Z), first, new ChunkCoordinates(B, Z), second)).thenCompose(batch -> {
            check(batch.results().values().stream().allMatch(r -> r == PublishChunkResult.PUBLISHED), "batch publication accepted");
            var manager = ((CraftWorld) world).getHandle().moonrise$getChunkTaskScheduler().chunkHolderManager;
            check(manager.getChunkHolder(A, Z) == null && manager.getChunkHolder(B, Z) == null, "publication creates no native holder or region");
            return Polar.publishChunk(world, A, Z, first);
        }).thenCompose(result -> {
            check(result == PublishChunkResult.ALREADY_PUBLISHED, "publication cannot overwrite");
            return Polar.publishChunk(world, 6000, 6000, second);
        }).thenCompose(result -> {
            check(result == PublishChunkResult.CAPACITY_EXCEEDED, "publication count is bounded");
            return CompletableFuture.allOf(Polar.generatePublishedChunk(world, A, Z), Polar.generatePublishedChunk(world, B, Z));
        }).thenCompose(ignored -> owned(world, A, Z, () -> {
            world.addPluginChunkTicket(A, Z, plugin);
            check(world.getBlockAt(X, 64, BLOCK_Z).getType() == Material.STONE, "prepared blocks generated");
            check(world.getBiome(X, 64, BLOCK_Z) == org.bukkit.block.Biome.DESERT, "prepared biome and blocks agree");
            check(((Chest) world.getBlockAt(X + 1, 65, BLOCK_Z + 1).getState()).getBlockInventory().isEmpty(), "generated chest has default inventory");
            return null;
        })).thenCompose(ignored -> owned(world, B, Z, () -> {
            world.addPluginChunkTicket(B, Z, plugin);
            check(world.getBlockAt(B * 16, 64, BLOCK_Z).getType() == Material.GOLD_BLOCK, "neighbor's publication was not mixed with another chunk"); return null;
        })).thenCompose(ignored -> Polar.publishChunk(world, A, Z, second)).thenCompose(result -> {
            check(result == PublishChunkResult.CHUNK_EXISTS, "existing chunk publication rejected");
            return Polar.discardPublishedChunk(world, A, Z);
        }).thenCompose(result -> {
            check(result == DiscardPublicationResult.NOT_PUBLISHED, "consumed payload released");
            return Polar.publishChunk(world, 6000, 6000, second);
        }).thenCompose(result -> Polar.discardPublishedChunk(world, 6000, 6000)).thenCompose(result -> {
            check(result == DiscardPublicationResult.DISCARDED, "unused publication explicitly discarded");
            return Polar.publishChunk(world, 6000, 6000, second);
        }).thenCompose(ignored -> Polar.destroyChunk(world, 6000, 6000)).thenCompose(result -> {
            check(result == DestroyChunkResult.ALREADY_ABSENT, "destroying absent chunk invalidates pending content");
            return Polar.generatePublishedChunk(world, 6000, 6000);
        }).thenAccept(result -> check(result == GenerateChunkResult.NOT_PUBLISHED, "destroyed publication cannot resurrect"));
    }
    private CompletableFuture<Void> patches(World world) {
        var one = ChunkPatch.builder().setBlock(0, 70, 0, "minecraft:diamond_block").build();
        return Polar.patchChunk(world, 7000, 7000, one, PatchOptions.DEFAULT).thenCompose(result -> {
            check(result.status() == PatchChunkResult.Status.NOT_LOADED, "default patch never loads missing chunks");
            return Polar.patchChunk(world, 7000, 7000, one, new PatchOptions(PatchOptions.LoadPolicy.LOAD_EXISTING, 64));
        }).thenCompose(result -> {
            check(result.status() == PatchChunkResult.Status.NOT_FOUND, "LOAD_EXISTING never generates missing chunks");
            check(((CraftWorld) world).getHandle().moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(7000, 7000) == null, "missing edits add no native holder");
            var invalid = ChunkPatch.builder().setBlock(0, 70, 0, "minecraft:not_a_block").build();
            return Polar.patchChunks(world, Map.of(new ChunkCoordinates(A, Z), one, new ChunkCoordinates(B, Z), invalid), PatchOptions.DEFAULT)
                    .handle((value, error) -> { check(error != null, "batch validates all states before any writes"); return null; });
        }).thenCompose(ignored -> owned(world, A, Z, () -> { check(world.getBlockAt(X, 70, BLOCK_Z).getType().isAir(), "invalid batch wrote nothing"); return null; }))
                .thenCompose(ignored -> {
                    int tick = Bukkit.getCurrentTick();
                    var slow = Polar.patchChunk(world, A, Z, ChunkPatch.builder().setRegion(0, 70, 0, 16, 74, 16, "minecraft:stone").build(), new PatchOptions(PatchOptions.LoadPolicy.REQUIRE_LOADED, 64));
                    slow.cancel(false);
                    var next = Polar.patchChunk(world, A, Z, one, PatchOptions.DEFAULT);
                    return delay(2).thenCompose(nothing -> Polar.destroyChunk(world, A, Z)).thenCompose(result -> {
                        check(result == DestroyChunkResult.BLOCKED, "native destruction blocked while cancelled result still has active writes");
                        return next;
                    }).thenCompose(result -> owned(world, A, Z, () -> {
                        check(result.status() == PatchChunkResult.Status.APPLIED, "FIFO patch completes after cancelled predecessor");
                        check(Bukkit.getCurrentTick() - tick >= 10, "write budget yields across ticks");
                        check(world.getBlockAt(X, 70, BLOCK_Z).getType() == Material.DIAMOND_BLOCK, "last queued write wins");
                        check(world.getBlockAt(X + 15, 73, BLOCK_Z + 15).getType() == Material.STONE, "cancelled future did not cancel native work"); return null;
                    }));
                });
    }
    private CompletableFuture<Void> patterns(World world) {
        BlockPattern mirror = BlockPattern.builder(2, 1, 1).setBlock(0, 0, 0, "minecraft:oak_stairs[facing=east]").build();
        return owned(world, A, Z, () -> { world.getBlockAt(X + 15, 80, BLOCK_Z).setType(Material.STONE, false); return null; })
                .thenCompose(ignored -> Polar.pastePattern(world, new BlockPosition(X + 15, 80, BLOCK_Z), mirror, new PatternOptions(0, true, false, PatchOptions.DEFAULT)))
                .thenCompose(batch -> { check(batch.results().size() == 2, "pattern partitions across chunk boundary"); return owned(world, A, Z, () -> {
                    check(world.getBlockAt(X + 15, 80, BLOCK_Z).getType().isAir(), "includeAir replaces volume"); return null;
                }); }).thenCompose(ignored -> owned(world, B, Z, () -> {
                    check(((Directional) world.getBlockAt(B * 16, 80, BLOCK_Z).getBlockData()).getFacing() == org.bukkit.block.BlockFace.WEST, "mirror transforms block orientation"); return null;
                })).thenCompose(ignored -> Polar.pastePattern(world, new BlockPosition(X + 5, 81, BLOCK_Z + 5), mirror, new PatternOptions(1, false, true, PatchOptions.DEFAULT)))
                .thenCompose(ignored -> owned(world, A, Z, () -> {
                    check(((Directional) world.getBlockAt(X + 5, 81, BLOCK_Z + 5).getBlockData()).getFacing() == org.bukkit.block.BlockFace.SOUTH, "rotation transforms position and orientation");
                    world.getBlockAt(X + 15, 82, BLOCK_Z).setType(Material.OBSIDIAN, false); return null;
                })).thenCompose(ignored -> Polar.pastePattern(world, new BlockPosition(X + 15, 82, BLOCK_Z), BlockPattern.builder(2, 1, 1).setBlock(1, 0, 0, "minecraft:diamond_block").build(), new PatternOptions(0, false, true, PatchOptions.DEFAULT)))
                .thenCompose(ignored -> owned(world, A, Z, () -> {
                    check(world.getBlockAt(X + 15, 82, BLOCK_Z).getType() == Material.OBSIDIAN, "ignoreAir preserves existing blocks"); return null;
                })).thenCompose(ignored -> {
                    var imported = new SchematicImport(mirror, new BlockPosition(0, 0, 0), 1, List.of("ignored inventory"));
                    return Polar.pasteSchematic(world, new BlockPosition(X, 84, BLOCK_Z), imported, PatternOptions.DEFAULT, false).handle((value, failure) -> {
                        check(failure != null, "schematic warnings require explicit acknowledgement"); return null;
                    });
                });
    }
    private CompletableFuture<Void> cleanup(World world) {
        BlockBounds volume = new BlockBounds(X, 64, BLOCK_Z, X + 4, 67, BLOCK_Z + 4);
        return owned(world, A, Z, () -> {
            var chest = (Chest) world.getBlockAt(X + 1, 65, BLOCK_Z + 1).getState(); chest.getBlockInventory().addItem(new ItemStack(Material.DIAMOND, 5));
            world.getBlockAt(X + 5, 65, BLOCK_Z + 5).setType(Material.COMPOSTER, false);
            ArmorStand in = world.spawn(new Location(world, X + 1.5, 66, BLOCK_Z + 1.5), ArmorStand.class); in.setGravity(false); inside = in.getUniqueId();
            ArmorStand out = world.spawn(new Location(world, X + 5.5, 66, BLOCK_Z + 5.5), ArmorStand.class); out.setGravity(false); outside = out.getUniqueId();
            in.addPassenger(out);
            var level = ((CraftWorld) world).getHandle();
            level.scheduleTick(new BlockPos(X + 2, 65, BLOCK_Z + 2), Blocks.COMPOSTER, 1000);
            level.scheduleTick(new BlockPos(X + 2, 65, BLOCK_Z + 2), Fluids.WATER, 1000);
            level.scheduleTick(new BlockPos(X + 5, 65, BLOCK_Z + 5), Blocks.COMPOSTER, 1000);
            return null;
        }).thenCompose(ignored -> Polar.destroyPatch(world, volume, PatchOptions.DEFAULT)).thenCompose(batch -> {
            check(batch.results().get(new ChunkCoordinates(A, Z)).status() == PatchChunkResult.Status.BLOCKED, "relationship outside volume blocks cleanup");
            return owned(world, A, Z, () -> {
                check(world.getBlockAt(X + 1, 65, BLOCK_Z + 1).getType() == Material.CHEST, "blocked cleanup preserved blocks and inventory");
                find(world, inside).eject(); return null;
            });
        }).thenCompose(ignored -> Polar.destroyPatch(world, volume, PatchOptions.DEFAULT)).thenCompose(batch -> {
            check(batch.results().get(new ChunkCoordinates(A, Z)).status() == PatchChunkResult.Status.APPLIED, "subvolume cleanup completes");
            return owned(world, A, Z, () -> {
                var level = ((CraftWorld) world).getHandle(); BlockPos old = new BlockPos(X + 2, 65, BLOCK_Z + 2);
                check(world.getBlockAt(X + 1, 65, BLOCK_Z + 1).getType().isAir() && level.getBlockEntity(new BlockPos(X + 1, 65, BLOCK_Z + 1)) == null, "block entity removed normally");
                check(level.getPoiManager().getType(old).isEmpty(), "POI removed inside volume");
                check(!level.getBlockTicks().hasScheduledTick(old, Blocks.COMPOSTER) && !level.getFluidTicks().hasScheduledTick(old, Fluids.WATER), "pending block and fluid ticks removed");
                check(level.getBlockTicks().hasScheduledTick(new BlockPos(X + 5, 65, BLOCK_Z + 5), Blocks.COMPOSTER), "outside scheduled tick preserved");
                check(world.getBlockAt(X + 5, 65, BLOCK_Z + 5).getType() == Material.COMPOSTER && find(world, outside) != null, "outside blocks and entities preserved");
                check(find(world, inside) == null, "inside non-player entity removed");
                check(nativeEntities(world).stream().noneMatch(e -> e instanceof net.minecraft.world.entity.item.ItemEntity), "clearing inventory created no drops");
                return null;
            });
        });
    }
    private CompletableFuture<Void> providerAndRaces(World world) {
        var explicit = PreparedChunk.builder(world.getMinHeight(), world.getMaxHeight()).setBlock(0, 64, 0, "minecraft:gold_block").fillBiome("minecraft:desert").build();
        return Polar.publishChunk(world, 1024, 1024, explicit).thenCompose(result -> Polar.generatePublishedChunk(world, 1024, 1024))
                .thenCompose(ignored -> owned(world, 1024, 1024, () -> {
                    check(world.getBlockAt(16384, 64, 16384).getType() == Material.GOLD_BLOCK && world.getBiome(16384, 64, 16384) == org.bukkit.block.Biome.DESERT, "explicit payload overrides provider for blocks and biomes"); return null;
                })).thenCompose(ignored -> world.getChunkAtAsync(1100, 1024)).thenCompose(ignored -> owned(world, 1100, 1024, () -> {
                    check(world.getBlockAt(17600, 64, 16384).getType() == Material.IRON_BLOCK && world.getBiome(17600, 64, 16384) == org.bukkit.block.Biome.FOREST, "provider generates exact concurrent content"); return null;
                })).thenCompose(ignored -> race(world, 0, explicit));
    }
    private CompletableFuture<Void> race(World world, int index, PreparedChunk content) {
        if (index == 5) return CompletableFuture.completedFuture(null);
        int c = 2000 + index * 32;
        var load = world.getChunkAtAsync(c, c);
        var publish = Polar.publishChunk(world, c, c, content);
        return load.thenCombine(publish, (chunk, result) -> result).thenCompose(result -> owned(world, c, c, () -> {
            check(result == PublishChunkResult.PUBLISHED || result == PublishChunkResult.GENERATION_STARTED || result == PublishChunkResult.CHUNK_EXISTS, "native generation race has a definitive admission result");
            Material expected = result == PublishChunkResult.PUBLISHED ? Material.GOLD_BLOCK : Material.AIR;
            check(world.getBlockAt(c * 16, 64, c * 16).getType() == expected, "generation race never mixes payload versions"); return null;
        })).thenCompose(ignored -> race(world, index + 1, content));
    }
    private CompletableFuture<Void> closing(World world) {
        return owned(world, B, Z, () -> { world.removePluginChunkTicket(B, Z, plugin); return null; }).thenCompose(ignored -> delay(120))
                .thenCompose(ignored -> Polar.patchChunk(world, B, Z, ChunkPatch.builder().setBlock(0, 90, 0, "minecraft:emerald_block").build(), new PatchOptions(PatchOptions.LoadPolicy.LOAD_EXISTING, 64)))
                .thenCompose(result -> {
                    check(result.status() == PatchChunkResult.Status.APPLIED, "LOAD_EXISTING patches retained RAM chunk");
                    var active = Polar.patchChunk(world, A, Z, ChunkPatch.builder().setRegion(0, 100, 0, 16, 116, 16, "minecraft:stone").build(), new PatchOptions(PatchOptions.LoadPolicy.REQUIRE_LOADED, 64));
                    return delay(3).thenCompose(ignored -> {
                        var queued = Polar.patchChunk(world, A, Z, ChunkPatch.builder().setBlock(0, 100, 0, "minecraft:gold_block").build(), PatchOptions.DEFAULT);
                        var folder = world.getWorldFolder().toPath();
                        try (var files = Files.walk(folder)) { check(files.noneMatch(p -> p.toString().endsWith(".mca") || p.toString().endsWith(".polar")), "editing and generation persist no world content"); }
                        catch (Exception error) { return CompletableFuture.failedFuture(error); }
                        return Polar.destroyWorld(world).thenCompose(nothing -> {
                            check(active.isDone(), "world destruction drains active patch");
                            return queued.handle((value, failure) -> { check(failure != null || value.status() == PatchChunkResult.Status.WORLD_DESTROYING, "world close cancels unstarted edits"); return null; });
                        });
                    });
                });
    }
    private CompletableFuture<Void> collectPayload(int attempt) {
        System.gc();
        return delay(2).thenCompose(ignored -> {
            if (payload.get() == null) { check(true, "consumed prepared payload collected while world remains loaded"); return CompletableFuture.completedFuture(null); }
            if (attempt >= 10) return CompletableFuture.failedFuture(new AssertionError("Prepared payload retained"));
            return collectPayload(attempt + 1);
        });
    }
    private List<net.minecraft.world.entity.Entity> nativeEntities(World world) {
        var holder = ((CraftWorld) world).getHandle().moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(A, Z);
        return holder.getEntityChunk().getAllEntities();
    }
    private Entity find(World world, UUID id) { return nativeEntities(world).stream().filter(e -> e.getUUID().equals(id)).findFirst().map(net.minecraft.world.entity.Entity::getBukkitEntity).orElse(null); }
    private <T> CompletableFuture<T> owned(World world, int x, int z, Callable<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        Bukkit.getRegionScheduler().execute(plugin, world, x, z, () -> {
            try { result.complete(action.call()); } catch (Throwable failure) { result.completeExceptionally(failure); }
        }); return result;
    }
    private CompletableFuture<Void> delay(long ticks) {
        CompletableFuture<Void> future = new CompletableFuture<>(); Bukkit.getGlobalRegionScheduler().runDelayed(plugin, ignored -> future.complete(null), ticks); return future;
    }
    private void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); assertions++; plugin.getLogger().info("PASS " + message);
    }
}
