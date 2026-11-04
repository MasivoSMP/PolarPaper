# Experimental Canvas server patch

`canvas-26.2-ephemeral.patch` applies to CraftCanvasMC/Canvas commit
`a631d9d7f16e2ea127cb731a31775206877038be` on `ver/26.2` (build 994-stable).
It is required by Polar's Canvas 26.2 ephemeral backend; stock Canvas is rejected.

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

Validated: ten unit tests and an actual disposable Canvas server smoke test (14 assertions), including
no ephemeral content files after forced regional saving and unchanged normal-world persistence.
The test plugin creates worlds and stops its server; **never install it in production**.

The patch also supplies lifecycle contract version 1: ticketless dispatch to existing regions and
targeted native chunk destruction without removing foreign tickets or force-removing entities.
Polar's actual patched-Canvas integration run passed 28 assertions with two regional tick threads,
including weak-reference collection before and after world destruction; Paper's regression passed 41.
Run Polar's two-thread regional integration suite from the Polar repository:

```sh
python3 src/integrationTest/run-server.py --platform canvas --server-jar /path/to/patched-canvas.jar --accept-eula
```

See `../EPHEMERAL_WORLDS.md` for validation scope and remaining load/player/heap tests.
Pure Folia remains unsupported. The patch is local/experimental and has not been submitted upstream.
