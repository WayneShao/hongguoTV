"""Verify pinned inputs and stage the Android library. Never modify the source cache."""
import argparse
from pathlib import Path
import shutil
import keyboard_assets
p = argparse.ArgumentParser()
p.add_argument('--vendor', type=Path)
p.add_argument('--fetch', action='store_true')
a = p.parse_args()
# Optional pre-existing cache; manifests always come from this repository.
sources, additions = keyboard_assets.prepare(fetch=a.fetch, vendor_dir=a.vendor)
out = keyboard_assets.ROOT / 'kotlin-tv/embedded-input/generated'
for source in sources:
    parts = source.parts
    relative = Path(*parts[parts.index('java') + 1:])
    target = out / 'java' / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
for name, data in additions.items():
    target = out / ('jniLibs/' + name[4:] if name.startswith('lib/') else name)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
print(f'Verified {len(sources)} Java sources, {len(additions)} payloads')
