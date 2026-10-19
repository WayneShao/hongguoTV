"""Pinned embedded-input dependencies. All downloaded bytes remain in ignored _local/vendor."""
import hashlib
import json
from pathlib import Path
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def digest(data): return hashlib.sha256(data).hexdigest()


def checked(path, expected):
    data = path.read_bytes()
    if digest(data) != expected: raise ValueError("Keyboard dependency hash mismatch: " + str(path))
    return data


def download(url, path, expected):
    if path.exists() and digest(path.read_bytes()) == expected: return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".download")
    urllib.request.urlretrieve(url, temporary)
    checked(temporary, expected); temporary.replace(path)


def prepare(fetch=False, vendor_dir=None):
    feelime = json.loads((ROOT / "config/keyboard-vendor.json").read_text(encoding="utf-8"))
    voice = json.loads((ROOT / "config/keyboard-voice.json").read_text(encoding="utf-8"))
    vendor = Path(vendor_dir) if vendor_dir else ROOT / "_local/vendor"
    additions = {}; sources = []
    for item in feelime["files"]:
        name = item["path"]; path = vendor / "feelime" / name
        if fetch: download(f"https://raw.githubusercontent.com/feelime/feelime/{feelime['commit']}/{name}", path, item["sha256"])
        data = checked(path, item["sha256"])
        if name.startswith("app/src/main/assets/engine-data/rime/"):
            destination = "assets/embedded-ime/rime/" + name.split("/rime/", 1)[1]
        elif name.startswith("app/src/main/jniLibs/"):
            destination = "lib/" + name.split("jniLibs/", 1)[1]
        else: destination = "assets/embedded-ime/notices/feelime-" + name
        additions[destination] = data
    if fetch:
        for name, entry in [("sherpa.aar", voice["aar"]), ("model.tar.bz2", voice["model_archive"])]:
            download(entry["url"], vendor / "sherpa" / name, entry["sha256"])
        with zipfile.ZipFile(vendor / "sherpa/sherpa.aar") as archive:
            path = vendor / "sherpa/jni/arm64-v8a/libsherpa-onnx-jni.so"; path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(archive.read("jni/arm64-v8a/libsherpa-onnx-jni.so"))
        names = {"encoder-epoch-99-avg-1.int8.onnx": "encoder.int8.onnx", "decoder-epoch-99-avg-1.onnx": "decoder.onnx",
                 "joiner-epoch-99-avg-1.int8.onnx": "joiner.int8.onnx", "tokens.txt": "tokens.txt"}
        with tarfile.open(vendor / "sherpa/model.tar.bz2", "r:bz2") as archive:
            for entry in archive:
                filename = entry.name.rsplit("/", 1)[-1]
                if entry.isfile() and filename in names:
                    path = vendor / "sherpa/model" / names[filename]; path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(archive.extractfile(entry).read())
    for item in voice["files"]:
        name = item["path"]; path = vendor / "sherpa" / name
        if name.startswith("java/"):
            if fetch:
                download(f"https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/{voice['version']}/sherpa-onnx/java-api/src/main/{name}", path, item["sha256"])
            checked(path, item["sha256"]); sources.append(path)
        elif name.startswith("model/"): additions["assets/embedded-ime/voice/" + name.split("/", 1)[1]] = checked(path, item["sha256"])
        elif name.startswith("jni/"): additions["lib/" + name.split("/", 1)[1]] = checked(path, item["sha256"])
        else: raise ValueError("Unknown voice dependency type: " + name)
    return sources, additions


if __name__ == "__main__":
    sources, additions = prepare(fetch=True)
    print(f"Verified {len(sources)} Java sources and {len(additions)} APK additions; {sum(map(len, additions.values()))} bytes")

