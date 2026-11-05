package live.minehub.polarpaper.core.ephemeral.edit;

import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class PreparedBlockEntityTest {
    private static byte[] tag() throws IOException {
        var tag = new CompoundTag(); tag.putString("id", "minecraft:chest");
        tag.putInt("x", 1); tag.putInt("y", 64); tag.putInt("z", 2);
        var item = new CompoundTag(); item.putByte("Slot", (byte)0); item.putString("id", "minecraft:diamond"); item.putInt("count", 7);
        var items = new ListTag(); items.add(item); tag.put("Items", items);
        var out = new ByteArrayOutputStream(); NbtIo.write(tag, new DataOutputStream(out)); return out.toByteArray();
    }
    @Test void immutableInventorySnapshot() throws Exception {
        byte[] bytes = tag(); var payload = new PreparedBlockEntity(1,64,2,"minecraft:chest",bytes);
        bytes[0] = 0; byte[] copy = payload.nbt(); copy[0] = 0;
        assertArrayEquals(tag(), payload.nbt());
        payload.data().remove("Items"); assertTrue(payload.data().contains("Items"));
        var prepared = PreparedChunk.builder(-64,320).setBlock(1,64,2,"minecraft:chest").blockEntity(payload).build();
        assertEquals(1,prepared.blockEntities().size());
        assertThrows(UnsupportedOperationException.class, () -> prepared.blockEntities().clear());
    }
    @Test void rejectsInvalidCoordinatesTypesAndLengths() throws Exception {
        byte[] bytes = tag();
        assertThrows(IllegalArgumentException.class, () -> new PreparedBlockEntity(16,64,2,"minecraft:chest",bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedBlockEntity(1,65,2,"minecraft:chest",bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedBlockEntity(1,64,2,"minecraft:barrel",bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedBlockEntity(1,64,2,"invalid",bytes));
        assertThrows(IllegalArgumentException.class, () -> new PreparedBlockEntity(1,64,2,"minecraft:chest",new byte[65537]));
        assertThrows(IllegalArgumentException.class, () -> new PreparedBlockEntity(1,64,2,"minecraft:chest",new byte[]{10}));
        var payload = new PreparedBlockEntity(1,64,2,"minecraft:chest",bytes);
        assertThrows(IllegalArgumentException.class, () -> PreparedChunk.builder(-64,64).blockEntity(payload));
        assertThrows(IllegalArgumentException.class, () -> PreparedChunk.builder(-64,320).blockEntity(payload).blockEntity(payload));
    }
}
