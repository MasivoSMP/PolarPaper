# Legacy Canvas server patch

`canvas-26.2-ephemeral.patch` is the legacy patch for CraftCanvasMC/Canvas commit
`a631d9d7f16e2ea127cb731a31775206877038be` on `ver/26.2` (build 994-stable).
It was required by the old patched-Canvas ephemeral backend. It is **not** used by the current
Pinac-backed adapter and is **not** applied by Pinac releases. It is kept only for historical
reference and for reproducing the old test setup. Current Pinac 26.2 uses its native persistent-world
APIs (`WorldCreator#persistent(false)`, `World#setPersistent(false)` and
`ServerLevel#canvas$destroyNonPersistentChunkAsync`).

In a clean clone at that revision:

```sh
git apply --whitespace=nowarn --check /path/to/PolarPaper/server-patches/canvas-26.2-ephemeral.patch
git apply --whitespace=nowarn /path/to/PolarPaper/server-patches/canvas-26.2-ephemeral.patch
./gradlew applyAllPatches
./gradlew :canvas-server:test --tests io.canvasmc.canvas.world.EphemeralStorageTestSuite
./gradlew createPaperclipJar :canvas-server:ephemeralIntegrationTestPlugin
```

Java 25 is required. The patched server is
`canvas-server/build/libs/canvas-paperclip-26.2.local-SNAPSHOT.jar`.
The patch includes `EPHEMERAL_SERVER_PATCH.md` with the API contract, limitations and test instructions.
Do not strip whitespace from nested generated `.patch` files: their blank context lines need a space.

Validated: eleven unit tests and an actual disposable Canvas server smoke test (14 assertions), including
no ephemeral content files after forced regional saving and unchanged normal-world persistence.
The test plugin creates worlds and stops its server; **never install it in production**.

The patch supplied lifecycle contract version 2: ticketless dispatch to existing regions and
targeted native chunk destruction without removing foreign tickets or force-removing entities.
Version 2 also added `executeOnExistingRegion` for bounded edits without loading or adding scheduler
tickets. That contract is obsolete; the current adapter uses Pinac's `persistent` API and native
chunk-destruction method (`canvas$destroyNonPersistentChunkAsync`) instead. The legacy patched-Canvas suite used two regional tick threads and
included publication/generation races, patches, pattern transforms, volume cleanup and weak-reference
collection; Paper runs the same editing suite.
Run Polar's two-thread regional integration suite from the Polar repository:

```sh
python3 src/integrationTest/run-server.py --platform canvas --server-jar /path/to/patched-canvas.jar --accept-eula
```

See `../EPHEMERAL_WORLDS.md` for validation scope and remaining load/player/heap tests.
Pure Folia remains unsupported. The patch is local/experimental and has not been submitted upstream.
