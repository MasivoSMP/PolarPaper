package live.minehub.polarpaper.core.ephemeral.edit;

import net.minecraft.nbt.*;
import java.io.*;
import java.util.Objects;

/** Immutable local-X/Z, absolute-Y payload. Uncompressed named-root binary NBT, not SNBT. */
public final class PreparedBlockEntity {
    public static final int MAX_BYTES = 65536;
    private final int x, y, z;
    private final String type;
    private final byte[] nbt;

    public PreparedBlockEntity(int x, int y, int z, String type, byte[] nbt) {
        if (x < 0 || x > 15 || z < 0 || z > 15) throw new IllegalArgumentException("Block entity outside chunk");
        if (type == null || type.length() > 256 || !type.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid block entity type");
        Objects.requireNonNull(nbt);
        if (nbt.length > MAX_BYTES) throw new IllegalArgumentException("Block entity exceeds 64 KiB");
        this.x = x; this.y = y; this.z = z; this.type = type; this.nbt = nbt.clone();
        CompoundTag tag = data();
        if (tag.contains("id") && !tag.getString("id").orElseThrow().equals(type)) throw new IllegalArgumentException("NBT type mismatch");
        for (String key : new String[]{"x", "y", "z"}) {
            if (tag.contains(key)) {
                int expected = key.equals("x") ? x : key.equals("y") ? y : z;
                if (tag.getInt(key).orElseThrow() != expected) throw new IllegalArgumentException("NBT coordinates must be chunk-local X/Z and absolute Y");
            }
        }
    }
    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }
    public String type() { return type; }
    public int encodedBytes() { return nbt.length; }
    public byte[] nbt() { return nbt.clone(); }
    public CompoundTag data() {
        try {
            var bytes = new ByteArrayInputStream(nbt);
            CompoundTag tag = NbtIo.read(new DataInputStream(bytes), new NbtAccounter(262144, 64));
            if (tag == null || bytes.available() != 0) throw new IllegalArgumentException("Invalid/trailing block entity NBT");
            return tag;
        } catch (IOException | RuntimeException error) { throw new IllegalArgumentException("Invalid or excessive block entity NBT", error); }
    }
}
