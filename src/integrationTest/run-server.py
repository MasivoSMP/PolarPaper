#!/usr/bin/env python3
"""Run opt-in integration plugins on an isolated, loopback-only disposable server."""
import argparse
from pathlib import Path
import shutil
import subprocess
import time
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--platform', choices=('paper', 'canvas'), required=True)
parser.add_argument('--server-jar', type=Path, required=True)
parser.add_argument('--directory', type=Path, default=Path('/tmp/opencode/polar-integration'))
parser.add_argument('--accept-eula', action='store_true', help='Explicitly accept the Minecraft EULA for this disposable instance')
args = parser.parse_args()
if not args.accept_eula:
    parser.error('Read https://aka.ms/MinecraftEULA and pass --accept-eula to run')
server_jar = args.server_jar.resolve(strict=True)
repo = Path(__file__).resolve().parents[2]
task, plugin, marker = ('ephemeralTestPlugin', 'PolarEphemeralTest.jar', 'integration-result.txt') if args.platform == 'paper' else (
    'canvasEphemeralTestPlugin', 'PolarCanvasEphemeralTest.jar', 'canvas-integration-result.txt')
subprocess.run(['./gradlew', 'build', task, '--console=plain'], cwd=repo, check=True)
root = args.directory.resolve() / f'{args.platform}-{int(time.time())}-{uuid.uuid4().hex[:8]}'
(root / 'plugins').mkdir(parents=True)
shutil.copy2(server_jar, root / 'server.jar')
shutil.copy2(repo / 'build/libs/polarpaper-2.3.2.jar', root / 'plugins/polarpaper.jar')
shutil.copy2(repo / 'build/libs' / plugin, root / 'plugins' / plugin)
(root / 'eula.txt').write_text('eula=true\n')
(root / 'server.properties').write_text(f'server-ip=127.0.0.1\nserver-port={25591 if args.platform == "paper" else 25592}\nonline-mode=false\nenable-status=false\n'
    'level-name=integration-world\nlevel-type=minecraft:flat\nview-distance=2\nsimulation-distance=2\nmax-players=1\n')
if args.platform == 'canvas':
    (root / 'config').mkdir()
    (root / 'config/paper-global.yml').write_text('threaded-regions:\n  threads: 2\n')
print(f'Disposable server directory: {root}', flush=True)
with (root / 'console.log').open('w') as log:
    result = subprocess.run(['java', '-XX:ActiveProcessorCount=4', '--enable-native-access=ALL-UNNAMED', '-Xms512M', '-Xmx1536M',
        '-jar', 'server.jar', '--nogui'], cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=240)
print((root / 'console.log').read_text()[-18000:])
if result.returncode != 0 or not (root / marker).exists() or not (root / marker).read_text().startswith('PASS'):
    raise SystemExit(f'Integration failed; inspect {root / "console.log"}')
print((root / marker).read_text())
