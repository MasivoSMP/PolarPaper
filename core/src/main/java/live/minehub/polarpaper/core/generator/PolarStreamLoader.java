package live.minehub.polarpaper.core.generator;

import ca.spottedleaf.moonrise.patches.starlight.light.SWMRNibbleArray;
import live.minehub.polarpaper.core.config.Config;
import live.minehub.polarpaper.core.source.PolarSource;
import live.minehub.polarpaper.core.userdata.WorldUserData;
import live.minehub.polarpaper.core.util.MemorySegmentReader;
import live.minehub.polarpaper.core.util.TaskFutures;
import live.minehub.polarpaper.core.world.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.ticks.LevelChunkTicks;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PolarStreamLoader extends PolarGenerator {
    private static final Logger LOGGER = LoggerFactory.getLogger(PolarStreamLoader.class);

    private final PolarDataConverter dataConverter;
    private short version;
    private int dataVersion;
    private byte[] userData = new byte[0];
    public PolarStreamLoader(Config config, @Nullable PolarSource polarSource, PolarWorldAccess worldAccess, PolarDataConverter dataConverter) {
        super(config, polarSource, worldAccess);
        this.dataConverter = dataConverter;

        this.dataVersion = dataConverter.defaultDataVersion();
    }

    //https://github.com/hollow-cube/polar/blob/main/src/main/java/net/hollowcube/polar/StreamingPolarLoader.java#L64
    public CompletableFuture<Void> load(World world) {
        if (getSource() == null) return CompletableFuture.completedFuture(null);

        try (Arena dstArena = Arena.ofConfined()) {
            final MemorySegment dst;

            dev.hallock.zstd.Zstd zstd = dev.hallock.zstd.Zstd.zstd();
            try (Arena srcArena = Arena.ofConfined();
                 ReadableByteChannel channel = getSource().read()) {

                long fileSize = getSource().size();

                final MemorySegment src;

                if (channel instanceof FileChannel fileChannel) {
                    src = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0L, fileSize, srcArena);
                } else {
                    final MemorySegment segment = srcArena.allocate(fileSize);
                    long offset = 0L; // readFully, but for large files
                    while (offset < fileSize) {
                        long n = channel.read(segment.asSlice(offset, fileSize - offset).asByteBuffer());
                        if (n < 0) {
                            throw new EOFException("Unexpected EOF: expected " + fileSize + " bytes, got " + offset);
                        }
                        offset += n;
                    }
                    src = segment.asReadOnly();
                }

                MemorySegmentReader reader = new MemorySegmentReader(src);

                var magic = reader.readInt();
                if (magic != PolarConstants.POLAR_MAGIC) throw new IOException("Invalid magic number: " + magic);

                this.version = reader.readShort();
                PolarConstants.validatePolarVersion(version);

                this.dataVersion = reader.readVarInt();

                var compressionByte = reader.readByte();
                PolarWorld.CompressionType compression = PolarWorld.CompressionType.fromId(compressionByte);
                if (compression == null) throw new IOException("Invalid compression type: " + compressionByte);

                int dataLength = reader.readVarInt();

                switch (compression) {
                    case NONE -> {
                        return readData(src.asSlice(reader.getOffset()), world);
                    }
                    // src should be unreachable following the dst copy.
                    case ZSTD -> {
                        var decompression = dstArena.allocate(dataLength);
                        zstd.decompress(decompression, dataLength, src.asSlice(reader.getOffset()), fileSize - reader.getOffset());
                        dst = decompression.asReadOnly();
                    }
                    default -> throw new UnsupportedOperationException(
                            "Unsupported compression type: " + compression
                    );
                }

            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
            // Now we can just read the dst buffer without having to worry about the extra footprint of src
            return readData(dst, world);
        }
    }

    private CompletableFuture<Void> readData(MemorySegment segment, World world) {
        MemorySegmentReader reader = new MemorySegmentReader(segment);

        byte minSection = reader.readByte();
        byte maxSection = reader.readByte();
        if (minSection >= maxSection) return CompletableFuture.failedFuture(new IOException("Invalid section range"));

        this.userData = reader.readByteArray();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int chunkCount = reader.readVarInt();
        try {
            for (int i = 0; i < chunkCount; i++) futures.add(readChunk(reader, world, maxSection - minSection + 1));
        } catch (Throwable error) { futures.add(CompletableFuture.failedFuture(error)); }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
    }

    private CompletableFuture<Void> readChunk(MemorySegmentReader reader, World world, int sectionCount) {
        int x = reader.readVarInt(), z = reader.readVarInt();
        PolarSection[] sections = new PolarSection[sectionCount];
        for (int i = 0; i < sectionCount; i++) sections[i] = PolarReader.readSection(dataConverter, dataVersion, reader);
        int count = reader.readVarInt();
        PolarChunk.BlockEntity[] entities = new PolarChunk.BlockEntity[count];
        for (int i = 0; i < count; i++) entities[i] = PolarReader.readBlockEntity(dataConverter, dataVersion, reader);
        int[][] heightmaps = PolarReader.readHeightmaps(reader);
        byte[] data = reader.readByteArray();
        PolarChunk input = new PolarChunk(x, z, sections, entities, heightmaps, data);
        return TaskFutures.runRegion(getWorldAccess().getPlugin(), world, x, z, () -> {
            ServerLevel level = ((CraftWorld) world).getHandle();
            NoUnloadLevelChunk chunk = input.createLevelChunk(level);
            for (PolarChunk.BlockEntity entity : entities) ChunkUtils.addBlockEntity(entity, chunk);
            ChunkUtils.insertChunk(level, chunk);
            getWorldAccess().loadChunkData(world, chunk, data);
            return null;
        });
    }

    @Override
    public @Nullable PolarWorld getPolarWorld() {
        return null;
    }

    @Override
    public void addInfoComponent(World world, TextComponent.Builder builder) {
        Vector3i offset = WorldUserData.readSchematicOffset(userData);

        builder.append(Component.text(" Version: ", NamedTextColor.AQUA))
                .append(Component.text(version, NamedTextColor.AQUA))
                .append(Component.text(" (", NamedTextColor.AQUA))
                .append(Component.text(dataVersion, NamedTextColor.AQUA))
                .append(Component.text(")", NamedTextColor.AQUA));

        if (offset != null) {
            builder.appendNewline();
            builder.append(Component.text(" Schematic center: ", NamedTextColor.AQUA));
            builder.append(Component.text(offset.x + ", " + offset.y + ", " + offset.z, NamedTextColor.AQUA));
        }
    }

    public byte[] getUserData() {
        return userData;
    }

    public void setUserData(byte[] userData) {
        this.userData = userData;
    }



}
