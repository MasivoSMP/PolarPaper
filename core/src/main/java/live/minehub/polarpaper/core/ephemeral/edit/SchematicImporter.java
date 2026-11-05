package live.minehub.polarpaper.core.ephemeral.edit;

import net.minecraft.nbt.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded Sponge .schem v2/v3 reader. No world access, generation, entity/NBT execution, or implicit data fixing. */
public final class SchematicImporter {
    private static final long MAX_NBT_BYTES = 32L * 1024 * 1024;
    private SchematicImporter() { }
    public static SchematicImport read(Path file, int targetDataVersion) throws IOException {
        try (InputStream input = Files.newInputStream(file)) { return read(input, targetDataVersion); }
    }
    /** Reads gzip NBT. The input is closed by the NBT reader. Older data is not automatically migrated. */
    public static SchematicImport read(InputStream input, int targetDataVersion) throws IOException {
        try { return decode(NbtIo.readCompressed(input, NbtAccounter.create(MAX_NBT_BYTES)), targetDataVersion); }
        catch (RuntimeException failure) { throw new IOException("Invalid or oversized schematic", failure); }
    }
    static SchematicImport decode(CompoundTag root, int targetDataVersion) throws IOException {
        CompoundTag tag = root.getCompound("Schematic").orElse(root);
        int version = tag.getInt("Version").orElseThrow(() -> new IOException("Missing schematic Version"));
        if (version != 2 && version != 3) throw new IOException("Only Sponge schematic v2/v3 is supported");
        int dataVersion = tag.getInt("DataVersion").orElseThrow(() -> new IOException("Missing DataVersion"));
        if (dataVersion < 0 || dataVersion > targetDataVersion) throw new IOException("Unsupported future/invalid DataVersion: " + dataVersion);
        int width = dimension(tag, "Width"), height = dimension(tag, "Height"), length = dimension(tag, "Length");
        if ((long) width * height > BlockPattern.MAX_BLOCKS / (long) length) throw new IOException("Schematic exceeds block limit");
        int[] offset = tag.getIntArray("Offset").orElse(new int[3]);
        if (offset.length != 3) throw new IOException("Offset must contain three integers");
        CompoundTag blocks = version == 3 ? tag.getCompound("Blocks").orElseThrow(() -> new IOException("Missing Blocks")) : tag;
        CompoundTag palette = blocks.getCompound("Palette").orElseThrow(() -> new IOException("Missing block palette"));
        Map<Integer, String> states = new HashMap<>();
        for (String key : palette.keySet()) {
            int id = palette.getInt(key).orElseThrow(() -> new IOException("Non-integer palette ID"));
            if (id < 0 || key.isBlank() || key.length() > 4096 || states.put(id, key) != null) throw new IOException("Invalid/duplicate palette ID");
        }
        if (states.isEmpty() || states.size() > BlockPattern.MAX_BLOCKS) throw new IOException("Invalid palette size");
        byte[] data = blocks.getByteArray(version == 3 ? "Data" : "BlockData").orElseThrow(() -> new IOException("Missing block data"));
        BlockPattern.Builder builder = BlockPattern.builder(width, height, length);
        int cursor = 0;
        for (int y = 0; y < height; y++) for (int z = 0; z < length; z++) for (int x = 0; x < width; x++) {
            int value = 0, count = 0, next;
            do {
                if (cursor >= data.length || count == 5) throw new IOException("Truncated/oversized varint");
                next = data[cursor++] & 255;
                if (count == 4 && (next & 0xf8) != 0) throw new IOException("Palette ID overflow");
                value |= (next & 127) << (count++ * 7);
            } while ((next & 128) != 0);
            String state = states.get(value);
            if (state == null) throw new IOException("Unknown palette ID: " + value);
            builder.setBlock(x, y, z, state);
        }
        if (cursor != data.length) throw new IOException("Trailing block data");
        List<String> warnings = new ArrayList<>();
        if (dataVersion != targetDataVersion) warnings.add("Older DataVersion: block states are not automatically migrated; destination validation may reject them");
        if (blocks.contains("BlockEntities") || tag.contains("TileEntities")) warnings.add("Block entity NBT/inventories are not imported");
        if (tag.contains("Entities")) warnings.add("Entities are not imported");
        if (tag.contains("Biomes") || tag.contains("BiomeData") || tag.contains("BiomePalette")) warnings.add("Schematic biomes are not imported by block patterns");
        return new SchematicImport(builder.build(), new BlockPosition(offset[0], offset[1], offset[2]), dataVersion, warnings);
    }
    private static int dimension(CompoundTag tag, String key) throws IOException {
        int value = tag.getShort(key).orElseThrow(() -> new IOException("Missing dimension: " + key)) & 65535;
        if (value == 0) throw new IOException("Zero schematic dimension");
        return value;
    }
}
