"""Patch the pinned sing SOCKS client without changing the shared module cache."""
import hashlib
import json
from pathlib import Path
import shutil
import stat
import subprocess
import sys

MODULE = "github.com/sagernet/sing"
VERSION = "v0.7.0-beta.2"
SUM = "h1:UImAKtHGQX205lGYYXKA2qnEeVSml+hKS1oaOwvA14c="
CLIENT_SHA256 = "9a732dff2ee50aa339bf60697471363265d8ec5294dfd437fa37e0f4cb9716e1"
ROOT = Path(__file__).resolve().parent.parent
source = Path(sys.argv[1]).resolve()
config = json.loads(subprocess.check_output(["go", "mod", "edit", "-json"], cwd=source, text=True))
if not any(row["Path"] == MODULE and row["Version"] == VERSION for row in config["Require"]):
    raise SystemExit("Unexpected sing version; review the SOCKS control lifecycle patch")
module = json.loads(subprocess.check_output(["go", "mod", "download", "-json", MODULE + "@" + VERSION], cwd=source, text=True))
if module.get("Sum") != SUM:
    raise SystemExit("Pinned sing module checksum mismatch")
upstream = Path(module["Dir"])
client = upstream / "protocol/socks/client.go"
if hashlib.sha256(client.read_bytes()).hexdigest() != CLIENT_SHA256:
    raise SystemExit("Unexpected sing SOCKS client source; review the control lifecycle patch")
original = "\t\treturn NewAssociatePacketConn(udpConn, address, tcpConn), nil"
replacement = """\t\tpacketConn := NewAssociatePacketConn(udpConn, address, tcpConn)
\t\t// RFC 1928: UDP association ends when the server closes TCP control.
\t\t// Monitor only outbound clients; server-side wrappers share this constructor.
\t\tgo func() {
\t\t\t_, _ = io.Copy(io.Discard, tcpConn)
\t\t\t_ = packetConn.Close()
\t\t}()
\t\treturn packetConn, nil"""
text = client.read_text(encoding="utf-8")
if text.count(original) != 1 or text.count('\t"context"\n') != 1:
    raise SystemExit("Unexpected sing SOCKS client layout")
text = text.replace('\t"context"\n', '\t"context"\n\t"io"\n', 1).replace(original, replacement, 1)
# A private build copy keeps other checkouts and cached upstream modules intact.
local = source / "fturn-sing"
test_relative = "protocol/socks/fturn_udp_control_test.go"
files = {p.relative_to(upstream).as_posix(): p for p in upstream.rglob("*") if p.is_file()}
if local.exists():
    extras = {p.relative_to(local).as_posix() for p in local.rglob("*") if p.is_file()} - files.keys() - {test_relative}
    if extras:
        raise SystemExit("Unexpected files in private sing build copy: " + ", ".join(sorted(extras)))
for relative, path in files.items():
    target = local / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.chmod(target.stat().st_mode | stat.S_IWRITE)
    shutil.copyfile(path, target)
(local / "protocol/socks/client.go").write_text(text, encoding="utf-8", newline="\n")
shutil.copyfile(ROOT / "scripts/tests/socks_udp_control_test.go", local / test_relative)
subprocess.run(["go", "mod", "edit", "-replace=" + MODULE + "=./fturn-sing"], cwd=source, check=True)
print("sing " + VERSION + ": outbound SOCKS UDP control EOF monitor applied (private build copy)")