"""Reject a stale local libbox AAR that lacks the verified SOCKS UDP close monitor."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

MONITOR = b"github.com/sagernet/sing/protocol/socks.(*Client).DialContext.func1"
ROOT = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser()
parser.add_argument("--aar", type=Path, default=ROOT / "app/libs/libbox.aar")
parser.add_argument("--report", type=Path)
args = parser.parse_args()
with zipfile.ZipFile(args.aar) as archive:
    library = archive.read("jni/arm64-v8a/libbox.so")
    assert archive.read("classes.jar"), "libbox Java bindings are missing"
if library[:6] != b"\x7fELF\x02\x01" or int.from_bytes(library[18:20], "little") != 183:
    raise SystemExit("libbox must contain an Android ARM64 shared library")
if b"./fturn-sing" not in library or MONITOR not in library:
    raise SystemExit("Stale libbox: SOCKS UDP control monitor missing. Run scripts/build-libbox.ps1 (Windows) or scripts/build-libbox.sh (Linux).")
report = {"aarSha256": hashlib.sha256(args.aar.read_bytes()).hexdigest(),
          "aarBinarySha256": hashlib.sha256(library).hexdigest(),
          "privateSingReplacement": True, "outgoingUdpControlMonitor": True}
if args.report:
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
print("libbox ARM64 private SOCKS UDP control monitor verified")
