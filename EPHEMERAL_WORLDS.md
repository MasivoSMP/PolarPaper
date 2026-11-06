# Ephemeral worlds

Targets Paper **26.1.2, 26.2 and 26.3**, plus **Pinac 26.2 with native persistent-world support**.
Pure Folia, stock Canvas and other versions are explicitly rejected before ephemeral creation.
The implementation uses Paper/Moonrise internals; a future Paper change can require an adapter update.
Live-tested on Paper **26.3 build 241**. The 26.1.2/26.2 adapters compile but have not yet been tested on live servers.

An ephemeral world starts **void** by default, without a template, source, retained `PolarWorld`, or Polar chunks.
An optional pure content provider or explicit publication can supply its first-generation blocks and biomes.
It keeps generated content in RAM until your plugin explicitly destroys it. There is no automatic
discard option and no force-unload option. Ordinary Polar worlds are unchanged.

### Pinac: native persistent worlds

Use Pinac 26.2. It exposes `WorldCreator#persistent(false)`, `World#setPersistent(false)` and
`ServerLevel#canvas$destroyNonPersistentChunkAsync(int, int)`. Polar creates ephemeral worlds with
`persistent(false)` before `createWorld`, so Pinac does not read or migrate existing metadata and does
not write chunks, entities, POI or per-world saved data. The flag is independent of `setAutoSave`; the
adapter still disables autosave as a second layer. Existing files are not deleted or rewritten.

Stock Canvas is rejected because it lacks these APIs. The legacy
`server-patches/canvas-26.2-ephemeral.patch` is retained only as historical reference and is not used
by the current adapter or Pinac releases.

Chunk destruction is dispatched to the existing owning region through
`ServerLevel#canvas$destroyNonPersistentChunkAsync`, without loading absent chunks or adding a scheduler
ticket. Pinac uses native unload stages and locks; it does not strip foreign tickets or force-remove
entities. Whole-world destruction uses `WorldUnloadAsyncEvent` and waits for the checked asynchronous
shutdown callback before deregistration validation and directory cleanup.

Pinac validation: storage unit tests plus a disposable-server smoke test with one non-persistent and
one persistent world. The non-persistent world produced no `.mca`, `data/` or `level.dat`; the normal
world saved correctly. Paper 26.3 remains validated by the existing 97-assertion suite.

## Configuration

`ephemeral` defaults to `false`. Set it before creation; it cannot be changed on a live world.

```yaml
worlds:
  dungeon:
    ephemeral: true
    autosaveIntervalTicks: -1
    saveOnStop: false
    loadOnStartup: true
```

Autosave, save-on-stop and an initial source/template are rejected for ephemeral worlds.
Every Polar save/export path rejects them, even if the caller supplies a different configuration or source.
Do not attach server tickets, enable vanilla autosaving, or alter the internal per-world persistence settings
unless you intentionally accept that destruction can fail or that the no-persistence contract is broken.

## Plugin API

Use `live.minehub.polarpaper.Polar` and `live.minehub.polarpaper.core.ephemeral.*`:

```java
Config config = Config.Builder.defaults()
        .ephemeral(true)
        .loadOnStartup(false)
        .build();

Polar.createEphemeralWorld("dungeon-instance", config).thenAccept(world -> {
    // Generate floors on the appropriate server thread. After moving players away:
    Polar.destroyChunks(world, List.of(
            new ChunkCoordinates(100, 200),
            new ChunkCoordinates(101, 200)
    )).thenAccept(result -> {
        // Inspect every result; a batch can partly succeed.
        result.results().forEach((coordinates, outcome) -> plugin.getLogger().info(coordinates + ": " + outcome));
    });

    // Once the dungeon ends and all players have left:
    // Polar.destroyWorld(world).thenRun(() -> ...);
});
```

Creation and destruction return futures; do not block the server thread with `join()`/`get()`.
Paper lifecycle work runs on its main thread. Pinac creation/world coordination runs globally and
chunk destruction runs on the owning region; completion callbacks need not run globally.
Plugins remain responsible for scheduling their
own generation, block changes and teleports. Cancelling a returned future does **not** cancel the
underlying native operation or release its coordination.

`destroyChunk(world, chunkX, chunkZ)` uses **chunk coordinates**, not block coordinates or `Chunk` objects:

| Result | Meaning |
| --- | --- |
| `DESTROYED` | Native unload finished and the holder was removed. |
| `ALREADY_ABSENT` | No holder existed; nothing was loaded for the request. |
| `BLOCKED` | A ticket, player, pending native work or non-unloadable entity prevents safe destruction. |
| `WORLD_DESTROYING` | World destruction started; new chunk operations are not accepted. |

The adapter never strips another plugin's tickets and never force-removes players or transient entities.
Release tickets and move players away before requesting destruction. A ticket can take server ticks to
expire or demote; `BLOCKED` is not automatically retried. Technical failures complete exceptionally.
Duplicate in-flight requests for the same chunk/world share an operation. Batches deduplicate coordinates
and return immutable per-coordinate results, not an atomic all-or-nothing transaction.

On revisiting a successfully destroyed chunk, generation runs again (void unless a provider supplies content); old blocks, block
entities and entities do not return. Without explicit destruction, chunk content remains in RAM.
Success means the server unload completed and Polar released its ownership, **not** immediate GC or
removal of references retained by your plugin, players, third-party plugins or the JVM.

## Prepared generation, patches and schematics

The additional models live in `live.minehub.polarpaper.core.ephemeral.edit.*`. These APIs are
**only for ephemeral worlds**; they do not modify ordinary Polar generation. No new YAML flags are needed.

### Publish before generation

```java
PreparedChunk payload = PreparedChunk.builder(world.getMinHeight(), world.getMaxHeight())
        .setRegion(0, 64, 0, 16, 65, 16, "minecraft:stone")
        .fillBiome("minecraft:desert")
        .build();
Polar.publishChunk(world, 100, 200, payload).thenCompose(result -> {
    if (result != PublishChunkResult.PUBLISHED) throw new IllegalStateException(result.toString());
    return Polar.generatePublishedChunk(world, 100, 200);
});
```

Publication accepts immutable blocks and quart-cell biomes covering the world's exact height;
omitted blocks are air. It **does not load or generate** the chunk. Explicit generation completes
after native full-chunk loading, not after a client acknowledgment. Publication uses native holder locks:
already existing full chunks return `CHUNK_EXISTS`; even a generation-halo/proto holder conservatively
returns `GENERATION_STARTED`. Other outcomes are `ALREADY_PUBLISHED`, `BUSY`, `WORLD_DESTROYING`
and `CAPACITY_EXCEEDED`. There is no overwrite operation. `publishChunks` returns per-coordinate results.
`discardPublishedChunk` releases only unused publication data and returns `BUSY` once a holder exists.
Loading consumes the payload. Successful chunk destruction invalidates pending content too;
blocked destruction preserves it. Publication is not a replacement for patching an existing chunk.

The creation overload `createEphemeralWorld(name, config, provider, editingOptions)` optionally accepts
a `ChunkContentProvider`. Its `prepare(ChunkGenerationContext)` returns a `PreparedChunk` or `null`
for void. It must be **pure, deterministic and concurrency-safe**: no live-world access, IO, waits or
reentrant Polar operations. Providers can be recomputed after bounded cache eviction, including
neighbor-biome queries. Exceptions fail native generation rather than silently generating void.
Priority is explicit publication, then provider, then void. Payloads contain no entities, inventory,
NBT or imported ticks; block entities start with normal default state.

### Edit existing chunks

```java
ChunkPatch patch = ChunkPatch.builder()
        .setRegion(0, 64, 0, 16, 65, 16, "minecraft:deepslate")
        .setBlock(2, 65, 2, "minecraft:chest[facing=north]")
        .build();
Polar.patchChunk(world, 100, 200, patch, PatchOptions.DEFAULT);
```

Patch x/z coordinates are local `0..15`; y is absolute. Omitted blocks are unchanged; overlapping
regions are applied in order, last write wins. `patchChunks` accepts a map or one shared patch plus
coordinates. Entire inputs are validated before writes. `REQUIRE_LOADED` (default) never loads chunks;
`LOAD_EXISTING` can reactivate a retained full RAM chunk but never generates missing chunks.
Generation in progress returns `BUSY`. Results include `APPLIED`, `NOT_LOADED`, `NOT_FOUND`,
`BUSY`, `BLOCKED`, `WORLD_DESTROYING` and `CAPACITY_EXCEEDED`, with processed-write/entity counts.
Counts are work performed, not necessarily changed blocks. Technical failure can occur after partial
writes: `PatchApplicationException` carries progress; `PatchBatchException` retains all settled results
and errors. There is **no rollback**, cross-chunk atomicity, automatic retry or immediate lighting/client
settlement guarantee. Normal patches use Bukkit updates without neighbor physics; callers must supply
valid multipart/support states. Replacing inventory blocks through a normal patch can cause ordinary
block-removal side effects; use `destroyPatch` when clearing without drops is required.

Per-chunk edits are FIFO. Chunk destruction is `BLOCKED` while edits are queued/active; world destruction
rejects new work, skips unstarted edits and drains active operations. Cancelling returned futures does not
cancel their native work. Work is budgeted per operation/world/global tick on the owning thread.
`ChunkEditingOptions.DEFAULT` allows 256 publications, 64 MiB estimated pending payload, 256 queued edits
and 8192 writes per world/tick; `PatchOptions` defaults to 2048 writes per operation/tick. Global limits
also bound payload/cache bytes, publications, queued metadata, preparation workers and write throughput.
Estimates are admission limits, **not exact JVM heap measurements**. Reject or retry explicitly when full.

### Patterns and Sponge schematics

`BlockPattern.builder(width, height, length)` creates an immutable block volume (maximum 1,048,576 blocks).
`pastePattern(world, origin, pattern, PatternOptions)` partitions it into ordinary chunk patches.
Options mirror local X first, then rotate clockwise by quarter turns, transforming block orientation too.
Including air replaces the volume; `ignoreAir` overlays only non-air blocks. Origins and bounds use integer
block coordinates; all volume bounds are min-inclusive/max-exclusive.

`SchematicImporter.read(pathOrStream, targetDataVersion)` imports gzip Sponge `.schem` **v2/v3** with
bounded NBT expansion, dimension/palette/varint validation and no world edits during import. Run file IO
off server tick threads. Newer data versions are rejected; older versions warn and are not automatically
data-fixed. Imported block entities, inventories, entities and biome data are explicitly ignored with
warnings. `pasteSchematic(..., acknowledgeWarnings)` requires acknowledgment of any warnings and adds
the schematic's offset to the supplied origin before the pattern transform. Malformed data is rejected.

### Clear a volume without destroying its chunks

`destroyPatch(world, new BlockBounds(minX, minY, minZ, maxX, maxY, maxZ), patchOptions)` clears blocks
to air without inventory drops, removes block entities, POIs and pending block/fluid ticks inside the
clipped volume, and removes non-player entities positioned inside it. A player, or a vehicle/passenger
relationship extending outside the volume or into an unowned region, blocks that chunk instead of
deleting outsiders. Outside content and foreign tickets are preserved. Checks repeat between slices;
movement or technical errors can leave partial progress. Other plugins can subsequently spawn/write/schedule
new work: this is not a global transaction. Chunks remain allocated; use `destroyChunks` to release an
entire floor's RAM. The same load policies, queue limits and budgets apply.

## World destruction and disk ownership

Use `Polar.destroyWorld(world)`, not `Bukkit.unloadWorld`: unchecked external unloads are cancelled.
Remove all players first. World destruction closes new chunk requests, waits for already-scheduled chunk
operations, respects the cancellable `WorldUnloadEvent` (Pinac: `WorldUnloadAsyncEvent`), closes native chunk/IO resources without saving,
deregisters the world, then deletes only that instance's owned directory. Failure is exceptional; a
failed destruction keeps the world closed to new chunk requests. Cancelled unloads and failed folder
cleanup can be retried with the same World. A partially failed **native Pinac shutdown** cannot safely
be retried: the server reports `FAIL_RESOURCE_CLOSE`, retains its unloading state and initiates shutdown.
Polar refuses a second native unload and leaves the directory/manifest untouched.

Temporary server metadata/directories are allowed; world **content** is not persisted. UUID ownership
manifests live in `plugins/polarpaper/ephemeral-instances`; each world directory has a matching marker.
Existing directories are never claimed. Cleanup refuses active worlds, mismatched markers and symlink
ancestors, and never follows inner symlinks. Failed cleanup leaves its manifest for retry on startup.
An interrupted reservation without a valid marker is deliberately left for manual inspection rather
than deleting an unproven directory. Do not edit markers or manifests on a running server.

During server shutdown, Polar leaves ephemeral directories alone while native resources are still open.
On the next startup, orphan cleanup runs before configured ephemeral worlds are recreated.

## Tests

```sh
./gradlew build ephemeralTestPlugin canvasEphemeralTestPlugin
```

Unit tests cover configuration validation, immutable models/results, payload limits, schematic validation
and ownership-safe cleanup. Both live suites additionally exercise publication/generation races, exact
blocks/biomes, bounded FIFO patches, patterns/orientation/air modes, schematic warnings, volume cleanup,
provider priority, payload weak references, no content files and closing with active/queued edits. The opt-in
`build/libs/PolarEphemeralTest.jar` runs lifecycle assertions on a **disposable** Paper server alongside
the built PolarPaper jar, then stops that server. Never install it on a production server: it creates
and destroys worlds named `integration_ephemeral` / `integration_persistent`, intentionally cancels an
unload and tampers with its own ownership marker to test retry behavior. Accept the Minecraft EULA
yourself before starting the server. A successful run logs `EPHEMERAL_INTEGRATION_PASS` and writes
`integration-result.txt` in the server directory.

The live suite checks ticket blocking, duplicate operations, partial batches, absent chunks without
loading, idle retention, native holder removal, void revisit, entity/block-entity lifecycle, rejected
saves/exports, cancelled unload, retryable deletion, same-name recreation, normal Polar save/unload,
cancelled futures, draining pending chunk operations, and weak-reference collection of destroyed chunks
while their world remains loaded, followed by collection of the detached world.

For reproducible disposable-server runs (Python 3 and Java 25):

```sh
python3 src/integrationTest/run-server.py --platform paper --server-jar /path/to/paper.jar --accept-eula
python3 src/integrationTest/run-server.py --platform canvas --server-jar /path/to/pinac.jar --accept-eula
```

`--accept-eula` is an explicit acceptance of the Minecraft EULA for the disposable server. Each run
uses a fresh directory under `/tmp/opencode/polar-integration`, binds loopback in offline mode, and
stops the server. Pinac uses **two regional tick threads** and tests two distant floors, ticket
blockers, partial results, absent coordinates, void revisit, weak references, cancelled/unchecked
async unloads, cleanup retry and pending-operation draining. Never install either test jar in production.

Actual-player blocking/teleport races, exhaustive split/merge stress, sustained memory-plateau tests,
heap-dump analysis and crash/restart scenarios still require a controlled load harness; the finite
integration suites do not establish production readiness or a universal absence of memory leaks.
