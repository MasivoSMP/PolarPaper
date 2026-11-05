package live.minehub.polarpaper.core.ephemeral.edit;

public record ChunkGenerationContext(String worldName, long seed, int chunkX, int chunkZ, int minY, int maxY) { }
