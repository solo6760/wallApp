#!/usr/bin/env python3
"""Stage the built debug APK and exact source tree without caches, secrets or SDK paths."""
from pathlib import Path
import hashlib
import shutil
import zipfile

root = Path(__file__).resolve().parent.parent
out = root / 'dist'
out.mkdir(exist_ok=True)
apk = root / 'app/build/outputs/apk/debug/app-debug.apk'
if not apk.is_file():
    raise SystemExit('Build first: python3 scripts/build.py :app:assembleDebug')
shutil.copy2(apk, out / 'AmbientFlow-debug.apk')
for name in ['LICENSE', 'THIRD_PARTY_NOTICES.md', 'README.md']:
    shutil.copy2(root / name, out / name)
excluded = {'.git', '.gradle', '.gradle-home', '.android', 'build', 'dist', '__pycache__'}
with zipfile.ZipFile(out / 'AmbientFlow-source.zip', 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for path in sorted(root.rglob('*')):
        rel = path.relative_to(root)
        if any(part in excluded for part in rel.parts):
            continue
        if path.is_file() and path.name != 'local.properties' and path.suffix not in {'.keystore', '.jks', '.pyc'}:
            archive.write(path, Path('AmbientFlow') / rel)
names = ['AmbientFlow-debug.apk', 'AmbientFlow-source.zip', 'LICENSE', 'THIRD_PARTY_NOTICES.md', 'README.md']
with (out / 'SHA256SUMS').open('w') as checksums:
    for name in names:
        path = out / name
        digest = hashlib.file_digest(path.open('rb'), 'sha256').hexdigest()
        checksums.write(f'{digest}  {name}\n')
        print(f'{name}: {path.stat().st_size:,} bytes SHA256 {digest}')
assert hashlib.sha256(apk.read_bytes()).digest() == hashlib.sha256((out / 'AmbientFlow-debug.apk').read_bytes()).digest()
