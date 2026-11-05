package live.minehub.polarpaper.core.ephemeral.edit;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class SchematicImporterTest {
    private static CompoundTag schematic(int version) {
        CompoundTag tag = new CompoundTag(); tag.putInt("Version", version); tag.putInt("DataVersion", 100);
        tag.putShort("Width", (short) 2); tag.putShort("Height", (short) 1); tag.putShort("Length", (short) 1);
        tag.putIntArray("Offset", new int[] {-3, 1, 2});
        CompoundTag palette = new CompoundTag(); palette.putInt("minecraft:stone", 0); palette.putInt("minecraft:air", 1);
        CompoundTag blocks = version == 3 ? new CompoundTag() : tag;
        blocks.put("Palette", palette); blocks.putByteArray(version == 3 ? "Data" : "BlockData", new byte[] {0, 1});
        if (version == 3) tag.put("Blocks", blocks);
        return tag;
    }
    @Test void readsV2AndV3WithExplicitOffsetAndAir() throws Exception {
        for (int version : new int[] {2, 3}) {
            CompoundTag tag = schematic(version); CompoundTag root = new CompoundTag(); root.put("Schematic", tag);
            var imported = SchematicImporter.decode(version == 3 ? root : tag, 100);
            assertEquals("minecraft:stone", imported.pattern().block(0, 0, 0)); assertEquals("minecraft:air", imported.pattern().block(1, 0, 0));
            assertEquals(new BlockPosition(-3, 1, 2), imported.offset()); assertTrue(imported.warnings().isEmpty());
        }
    }
    @Test void compressedRoundTripAndCorruptionAreBounded() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream(); NbtIo.writeCompressed(schematic(2), output);
        assertEquals(2, SchematicImporter.read(new ByteArrayInputStream(output.toByteArray()), 100).pattern().volume());
        assertThrows(IOException.class, () -> SchematicImporter.read(new ByteArrayInputStream(new byte[] {1, 2, 3}), 100));
    }
    @Test void rejectsFutureVersionDimensionsPaletteAndVarintErrors() {
        CompoundTag future = schematic(4); assertThrows(IOException.class, () -> SchematicImporter.decode(future, 100));
        assertThrows(IOException.class, () -> SchematicImporter.decode(schematic(2), 99));
        CompoundTag huge = schematic(2); huge.putShort("Width", (short) -1); huge.putShort("Height", (short) -1);
        assertThrows(IOException.class, () -> SchematicImporter.decode(huge, 100));
        CompoundTag duplicate = schematic(2); duplicate.getCompound("Palette").orElseThrow().putInt("minecraft:dirt", 0);
        assertThrows(IOException.class, () -> SchematicImporter.decode(duplicate, 100));
        for (byte[] invalid : new byte[][] {{0}, {0, 1, 0}, {2, 1}, {-128, -128, -128, -128, -128, 0}, {-1, -1, -1, -1, 127}}) {
            CompoundTag tag = schematic(2); tag.putByteArray("BlockData", invalid);
            assertThrows(IOException.class, () -> SchematicImporter.decode(tag, 100));
        }
    }
    @Test void warnsRatherThanImportingEntitiesInventoriesAndBiomes() throws Exception {
        CompoundTag tag = schematic(3); ListTag unsupported = new ListTag(); unsupported.add(new CompoundTag());
        tag.put("Entities", unsupported); tag.getCompound("Blocks").orElseThrow().put("BlockEntities", unsupported); tag.put("Biomes", new CompoundTag());
        assertEquals(4, SchematicImporter.decode(tag, 101).warnings().size());
    }
}
