"""Validate the APK against both pinned source builds and collect release files."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import zipfile

version = os.environ["RELEASE_TAG"].removeprefix("v")
metadata = json.loads(Path("app/build/outputs/apk/release/output-metadata.json").read_text())
element = metadata["elements"][0]
assert metadata["applicationId"] == "com.litar.freeturn"
assert element["versionName"] == version
major, minor, patch = map(int, version.split("."))
assert element["versionCode"] == major * 10000 + minor * 100 + patch
apk = Path("app/build/outputs/apk/release") / element["outputFile"]
out = Path("dist")
out.mkdir(exist_ok=True)
with zipfile.ZipFile(apk) as archive:
    names = archive.namelist()
    for required in ["assets/rules/ru-aggregated.zone", "lib/arm64-v8a/libbox.so"]:
        assert required in names, required
    assert archive.read("assets/rules/ru-aggregated.zone").strip()
    assert not any(name.endswith(("/libwg-go.so", "/libwg-quick.so", "/libwg.so")) for name in names)
    for name, binary in [("freeturn", "libfreeturn.so"), ("raw", "libfturnraw.so")]:
        pin = dict(line.split("=", 1) for line in Path(name + "-core.properties").read_text().splitlines()
                   if line and not line.startswith("#"))
        manifest = json.loads(Path(pin["source"], "SOURCE.json").read_text())
        embedded = json.loads(archive.read("assets/" + name + "-core.json"))
        assert embedded["version"] == manifest["coreVersion"] == pin["version"]
        assert embedded["sourceSnapshotSha256"] == manifest["sourceSnapshotSha256"]
        digest = hashlib.sha256(archive.read("lib/arm64-v8a/" + binary)).hexdigest()
        assert digest == embedded["binarySha256"]
        assert digest == hashlib.sha256(Path("app/src/main/jniLibs/arm64-v8a", binary).read_bytes()).hexdigest()
        (out / (name + "-core-" + pin["version"] + ".json")).write_text(json.dumps(embedded, indent=2) + "\n")
        source = Path(pin["source"])
        shutil.make_archive(str(out / ("fturn-" + name + "-core-" + pin["version"] + "-android-source")),
                            "gztar", source.parent, source.name)
shutil.copy2(apk, out / ("fturn-" + version + "-arm64-v8a.apk"))
shutil.copy2("third_party/raw-core-NOTICES.txt", out / "RAW-CORE-THIRD-PARTY-NOTICES.txt")
shutil.copy2("third_party/freeturn-core/LICENSE", out / "FREETURN-CORE-LICENSE.txt")
