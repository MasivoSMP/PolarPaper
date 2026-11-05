package live.minehub.polarpaper.core.ephemeral.edit;

import java.util.List;

/** Offset is explicit: paste at origin.add(offset.x(), offset.y(), offset.z()). Warnings require caller acknowledgement. */
public record SchematicImport(BlockPattern pattern, BlockPosition offset, int dataVersion, List<String> warnings) {
    public SchematicImport { warnings = List.copyOf(warnings); }
}
