#!/usr/bin/env python3
"""Verify and build the pinned RAW Android core and its APK provenance metadata."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parent.parent

def properties(path):
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8").splitlines()
                if line.strip() and not line.startswith("#"))

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path)
    args = parser.parse_args()
    config = properties(ROOT / "raw-core.properties")
    version, toolchain = config["version"], config["toolchain"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise SystemExit("RAW core version must be major.minor.patch")
    source = (args.source or ROOT / config["source"]).resolve()
    manifest = json.loads((source / "SOURCE.json").read_text(encoding="utf-8"))
    if manifest["coreVersion"] != version or manifest["goToolchain"] != toolchain:
        raise SystemExit("RAW source version/toolchain differs from the pinned build configuration")
    hashes = []
    for item in manifest["files"]:
        path = (source / item["path"]).resolve()
        if not path.is_relative_to(source) or not path.is_file():
            raise SystemExit("Invalid RAW source path")
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != item["sha256"]:
            raise SystemExit("RAW source checksum mismatch: " + item["path"])
        hashes.append(actual + "  " + item["path"] + "\n")
    snapshot = hashlib.sha256("".join(hashes).encode()).hexdigest()
    if snapshot != manifest["sourceSnapshotSha256"]:
        raise SystemExit("RAW source snapshot checksum mismatch")
    if (source / "internal/buildinfo/core-version.txt").read_text().strip() != version:
        raise SystemExit("RAW default core version differs from the release version")
    # Refuse unlisted source inputs: a new .go or embedded resource must be reviewed
    # and added to SOURCE.json before it can affect the release binary.
    allowed = {item["path"] for item in manifest["files"]} | {"SOURCE.json", "README.android.md"}
    if {p.relative_to(source).as_posix() for p in source.rglob("*") if p.is_file()} != allowed:
        raise SystemExit("RAW snapshot contains unlisted or missing files")
    target = ROOT / "app/src/main/jniLibs/arm64-v8a/libfturnraw.so"
    metadata = ROOT / "app/build/generated/rawCoreAssets/raw-core.json"
    target.parent.mkdir(parents=True, exist_ok=True)
    metadata.parent.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ, GOOS="android", GOARCH="arm64", CGO_ENABLED="0",
               GOTELEMETRY="off", GOTOOLCHAIN=toolchain)
    flags = "-checklinkname=0 -s -w -X github.com/samosvalishe/free-turn-proxy/internal/buildinfo.Version=" + version
    subprocess.run(["go", "build", "-mod=readonly", "-buildvcs=false", "-trimpath", "-buildmode=pie",
                    "-ldflags", flags, "-o", str(target), "./cmd/raw-client"], cwd=source, env=env, check=True)
    compiler = subprocess.check_output(["go", "version"], cwd=source, env=env, text=True).strip()
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    metadata.write_text(json.dumps({"version": version, "sourceSnapshotSha256": snapshot,
        "sourceBaseCommit": manifest["sourceBaseCommit"], "binarySha256": digest,
        "goVersion": compiler}, indent=2) + "\n", encoding="utf-8")
    print("RAW core " + version + " built and verified; SHA-256: " + digest)

if __name__ == "__main__":
    main()
