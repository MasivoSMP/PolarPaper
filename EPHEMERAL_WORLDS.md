# Ephemeral worlds

Targets Paper **26.1.2, 26.2 and 26.3**, plus **Canvas 26.2 with the bundled server patch**.
Pure Folia, stock Canvas and other versions are explicitly rejected before ephemeral creation.
The implementation uses Paper/Moonrise internals; a future Paper change can require an adapter update.
Live-tested on Paper **26.3 build 241**. The 26.1.2/26.2 adapters compile but have not yet been tested on live servers.

An ephemeral world starts **void**, without a template, source, retained `PolarWorld`, or Polar chunks.
It keeps generated content in RAM until your plugin explicitly destroys it. There is no automatic
discard option and no force-unload option. Ordinary Polar worlds are unchanged.

### Canvas: patched server required

Install `server-patches/canvas-26.2-ephemeral.patch` against Canvas commit
`a631d9d7f16e2ea127cb731a31775206877038be` (build 994-stable); see `server-patches/README.md`.
Stock Canvas forces regional saving even for `unloadWorldAsync(..., false, ...)`, including dirty
protochunks and POI-only holders, and can log resource-close failures without failing its callback.
The patch adds immutable no-content-IO guards and checked closure, not a temporary disk fallback.
The plugin verifies native lifecycle contract version 1 before creating an ephemeral Canvas world.
Ordinary Canvas Polar worlds keep their existing behavior.
Live-tested on that patched Canvas revision: **28 assertions passed with two regional tick threads**;
Paper 26.3 regression: **41 assertions passed**.

Chunk destruction is dispatched to the existing owning region using a native **ticketless** queue:
it neither loads absent chunks nor adds a scheduler ticket that would block its own unload. Native
queues migrate tasks on split/merge and retire them when a region disappears. Regional destruction
uses native unload stages and locks. Whole-world destruction uses `WorldUnloadAsyncEvent` and waits
for the checked asynchronous shutdown callback before deregistration validation and directory cleanup.

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
Paper lifecycle work runs on its main thread. Canvas creation/world coordination runs globally and
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

On revisiting a successfully destroyed chunk, normal void generation runs again; old blocks, block
entities and entities do not return. Without explicit destruction, chunk content remains in RAM.
Success means the server unload completed and Polar released its ownership, **not** immediate GC or
removal of references retained by your plugin, players, third-party plugins or the JVM.

## World destruction and disk ownership

Use `Polar.destroyWorld(world)`, not `Bukkit.unloadWorld`: unchecked external unloads are cancelled.
Remove all players first. World destruction closes new chunk requests, waits for already-scheduled chunk
operations, respects the cancellable `WorldUnloadEvent` (Canvas: `WorldUnloadAsyncEvent`), closes native chunk/IO resources without saving,
deregisters the world, then deletes only that instance's owned directory. Failure is exceptional; a
failed destruction keeps the world closed to new chunk requests. Cancelled unloads and failed folder
cleanup can be retried with the same World. A partially failed **native Canvas shutdown** cannot safely
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

Unit tests cover configuration validation, immutable results and ownership-safe cleanup. The opt-in
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
python3 src/integrationTest/run-server.py --platform canvas --server-jar /path/to/patched-canvas.jar --accept-eula
```

`--accept-eula` is an explicit acceptance of the Minecraft EULA for the disposable server. Each run
uses a fresh directory under `/tmp/opencode/polar-integration`, binds loopback in offline mode, and
stops the server. Canvas uses **two regional tick threads** and tests two distant floors, ticket
blockers, partial results, absent coordinates, void revisit, weak references, cancelled/unchecked
async unloads, cleanup retry and pending-operation draining. Never install either test jar in production.

Actual-player blocking/teleport races, exhaustive split/merge stress, sustained memory-plateau tests,
heap-dump analysis and crash/restart scenarios still require a controlled load harness; the finite
integration suites do not establish production readiness or a universal absence of memory leaks.
