package live.minehub.polarpaper.core.ephemeral.edit;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Owned-region native cleanup. Does not replace a holder, remove tickets, or clear unrelated world state. */
public final class ChunkVolumeCleanup {
    private ChunkVolumeCleanup() { }
    public static void clearBlock(ServerLevel level, int x, int y, int z) {
        TickThread.ensureTickThread(level, x >> 4, z >> 4, "Volume cleanup requires chunk ownership");
        BlockPos position = new BlockPos(x, y, z);
        // Normal setBlock lifecycle, lighting and client updates, but no inventory drops/on-place/neighbor side effects.
        level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_ALL_SIDEEFFECTS);
        // setBlock owns POI removal too; removing it twice produces stale-POI errors.
    }
    public static void clearTicks(ServerLevel level, BlockBounds bounds) {
        TickThread.ensureTickThread(level, bounds.minX() >> 4, bounds.minZ() >> 4, "Volume cleanup requires chunk ownership");
        BoundingBox box = new BoundingBox(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX() - 1, bounds.maxY() - 1, bounds.maxZ() - 1);
        level.getBlockTicks().clearArea(box);
        level.getFluidTicks().clearArea(box);
    }
}
