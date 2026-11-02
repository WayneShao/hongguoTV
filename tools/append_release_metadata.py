"""Append latest-release updater metadata from verified APK facts, not prose.

Usage: python tools/append_release_metadata.py VERSION verification.json NOTES.md
verification.json: three records with file, package, version, code and minSdk.
"""
import json
import re
import sys
from pathlib import Path


def append_metadata(version, records, notes):
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Expected X.Y.Z version")
    variants = {}
    for variant, package, suffix in (
        ("TV", "com.hongguotv.nativeapp", "local"),
        ("X3Pro-Keyboard", "com.hongguotv.rayneo", "rayneo-keyboard"),
        ("X3Pro-Full", "com.hongguotv.rayneo", "rayneo-full"),
    ):
        matches = [r for r in records if r["file"] == f"hongguoTV-{variant}-{version}.apk"]
        if len(matches) != 1:
            raise ValueError(f"Missing/duplicate verified APK: {variant}")
        row = matches[0]
        if row["package"] != package or row["version"] != f"{version}-{suffix}":
            raise ValueError(f"Wrong APK identity: {variant}")
        code, sdk = row["code"], row["minSdk"]
        if not 1 <= code <= 2147483647 or not 26 <= sdk <= 100:
            raise ValueError("Invalid code/minSdk")
        variants[variant] = dict(packageName=package, versionName=row["version"], versionCode=code, minSdk=sdk)
    marker = "<!-- hongguotv-update "
    body = notes.split(marker)[0].rstrip()
    metadata = json.dumps(dict(schema=1, variants=variants), separators=(",", ":"))
    return f"{body}\n\n{marker}{metadata} -->\n"


if __name__ == "__main__":
    release_version, facts_path, notes_path = sys.argv[1:]
    facts = json.loads(Path(facts_path).read_text(encoding="utf-8-sig"))
    path = Path(notes_path)
    path.write_text(append_metadata(release_version, facts, path.read_text(encoding="utf-8-sig")), encoding="utf-8")
