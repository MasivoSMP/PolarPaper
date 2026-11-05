package live.minehub.polarpaper.integration;

import live.minehub.polarpaper.Polar;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.ephemeral.ChunkCoordinates;
import live.minehub.polarpaper.core.ephemeral.edit.*;
import live.minehub.polarpaper.core.util.TaskFutures;
import live.minehub.polarpaper.core.world.*;
import live.minehub.polarpaper.nms.VersionUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.item.Items;
import org.bukkit.*;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable server only. Tests live NBT restoration, not the native ephemeral lifecycle. */
public final class PreparedNbtIntegrationPlugin extends JavaPlugin {
    private int assertions;
    private void check(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private void rejected(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; } throw new AssertionError("Expected rejection"); }
    private static byte[] encode(CompoundTag tag) {
        try { var bytes = new ByteArrayOutputStream(); NbtIo.write(tag, new DataOutputStream(bytes)); return bytes.toByteArray(); }
        catch (IOException error) { throw new UncheckedIOException(error); }
    }
    @Override public void onEnable() {
        if (!Boolean.getBoolean("polar.nbtTest")) return;
        Bukkit.getGlobalRegionScheduler().runDelayed(this, ignored -> {
            var config = Config.Builder.defaults().autoSaveIntervalTicks(-1).saveOnStop(false).loadOnStartup(false).build();
            var access = new PolarWorldAccess() { public org.bukkit.plugin.Plugin getPlugin() { return PreparedNbtIntegrationPlugin.this; } };
            Polar.createWorld(new PolarWorld((byte)-4, (byte)19, List.of(new PolarChunk(0, 0, 24))), "nbt_qa", config, access, PolarDataConverter.DEFAULT)
                    .thenCompose(world -> TaskFutures.runRegion(this, world, 0, 0, () -> { exercise(world); return world; }))
                    .thenCompose(world -> VersionUtil.getWorldUnloader().unloadWorld(this, world).thenAccept(ok -> check(ok && Bukkit.getWorld(world.getUID()) == null, "World unload")))
                    .orTimeout(90, TimeUnit.SECONDS).whenComplete((ok, error) -> finish(error));
        }, 40);
    }
    private void exercise(World world) {
        var level = ((CraftWorld)world).getHandle();
        var nativeChunk = (net.minecraft.world.level.chunk.LevelChunk)level.moonrise$getChunkTaskScheduler().chunkHolderManager.getChunkHolder(0, 0).getCurrentChunk();
        var builder = PreparedChunk.builder(-64, 320);
        try {
            var schematic = SchematicImporter.read(Path.of(System.getProperty("polar.testSchematic")), net.minecraft.SharedConstants.getCurrentVersion().dataVersion().version());
            var pattern = schematic.pattern();
            int copied = 0;
            for (int y = 0; y < Math.min(64, pattern.height()); y++) for (int z = 0; z < Math.min(16, pattern.length()); z++) for (int x = 0; x < Math.min(16, pattern.width()); x++) {
                String state = pattern.block(x,y,z); builder.setBlock(x, y, z, state);
                world.getBlockAt(x,y,z).setBlockData(Bukkit.createBlockData(state), false); copied++;
            }
            check(copied > 0, "Real schematic geometry decoded and placed");
        } catch (IOException error) { throw new UncheckedIOException(error); }
        Material[] materials = {Material.CHEST, Material.BARREL, Material.FURNACE};
        List<byte[]> snapshots = new ArrayList<>();
        for (int i = 0; i < materials.length; i++) {
            int x = 1 + i * 2;
            world.getBlockAt(x, 80, 1).setType(materials[i], false);
            var container = (BaseContainerBlockEntity)nativeChunk.getBlockEntity(new BlockPos(x,80,1));
            container.setItem(0, new net.minecraft.world.item.ItemStack(Items.DIAMOND, 7 + i));
            CompoundTag tag = container.saveWithFullMetadata(level.registryAccess());
            byte[] encoded = encode(tag); snapshots.add(encoded.clone());
            var payload = new PreparedBlockEntity(x,80,1,tag.getString("id").orElseThrow(),encoded);
            builder.setBlock(x,80,1,"minecraft:" + materials[i].name().toLowerCase(Locale.ROOT)).blockEntity(payload);
            encoded[0] = 0; check(Arrays.equals(payload.nbt(), snapshots.get(i)), "Defensive input copy");
            byte[] returned = payload.nbt(); returned[0] = 0; check(Arrays.equals(payload.nbt(), snapshots.get(i)), "Defensive output copy");
            container.clearContent();
        }
        var prepared = builder.build();
        var store = new ChunkContentStore(null, ChunkEditingOptions.DEFAULT);
        store.publish(new ChunkCoordinates(0,0), store.resolve(prepared, -64, 320));
        store.restore(world, nativeChunk); store.checkRestoration();
        for (int i = 0; i < 3; i++) {
            var container = (BaseContainerBlockEntity)nativeChunk.getBlockEntity(new BlockPos(1+i*2,80,1));
            check(container.getItem(0).is(Items.DIAMOND) && container.getItem(0).getCount() == 7+i, "Live container inventory restored");
        }
        byte[] tag = snapshots.getFirst();
        rejected(() -> new PreparedBlockEntity(16,80,1,"minecraft:chest",tag));
        rejected(() -> new PreparedBlockEntity(1,80,1,"minecraft:barrel",tag));
        rejected(() -> new PreparedBlockEntity(1,80,1,"minecraft:chest",new byte[65537]));
        rejected(() -> new PreparedBlockEntity(1,80,1,"minecraft:chest",new byte[]{10}));
        rejected(() -> PreparedChunk.builder(-64,320).blockEntity(prepared.blockEntities().getFirst()).blockEntity(prepared.blockEntities().getFirst()));
        var mismatch = PreparedChunk.builder(-64,320).blockEntity(prepared.blockEntities().getFirst()).build();
        rejected(() -> store.resolve(mismatch,-64,320));
        var deep = new CompoundTag(); var next = deep;
        for (int i=0;i<70;i++) { var child=new CompoundTag(); next.put("child",child); next=child; }
        byte[] depth = encode(deep);
        rejected(() -> new PreparedBlockEntity(1,80,1,"minecraft:chest",depth));
        check(nativeChunk.mustNotSave, "No-save chunk flag retained"); store.release();
    }
    private void finish(Throwable error) {
        TaskFutures.runGlobal(this, () -> {
            try { getDataFolder().mkdirs(); Files.writeString(getDataFolder().toPath().resolve("results.txt"), "server="+Bukkit.getVersion()+"\nassertions="+assertions+"\nresult="+(error==null?"PASS":"FAIL "+error)+"\n"); }
            catch (IOException write) { throw new UncheckedIOException(write); }
            if(error!=null) getLogger().log(java.util.logging.Level.SEVERE,"NBT integration failed",error);
            Bukkit.shutdown(); return null;
        });
    }
}
