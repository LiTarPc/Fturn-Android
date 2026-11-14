"""Patch the pinned sing SOCKS control lifecycles without changing the shared module cache."""
import hashlib
import json
from pathlib import Path
import shutil
import stat
import subprocess
import sys

MODULE = "github.com/sagernet/sing"
VERSION = "v0.9.7-0.20261023115459-5dc452e537bf"
SUM = "h1:qWlGERjsI0F1I9e1W5Z/ARk/9KEN7xIas6e2QHEypUM="
CLIENT_SHA256 = "85015fbfec7c5d7783c07c435bc71f4b38ac46c44dc4f1678c94f841b9ad7e01"
HANDSHAKE_SHA256 = "519634a01cba3041477aac4c5a767424b9167708a96af90bdf746a970aad3be3"
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
handshake = upstream / "protocol/socks/handshake.go"
if hashlib.sha256(handshake.read_bytes()).hexdigest() != HANDSHAKE_SHA256:
    raise SystemExit("Unexpected sing SOCKS server source; review the control lifecycle patch")
server_text = handshake.read_text(encoding="utf-8")
start = server_text.index("\t\t\terr = socks5.WriteResponse(conn, socks5.Response{", server_text.index("case socks5.CommandUDPAssociate:", server_text.index("func HandleConnectionEx")))
end = server_text.index("\t\t\treturn nil", start)
server_text = server_text[:start] + """\t\t\tpacketCtx, association := newFturnServerAssociateConn(ctx, udpConn, conn, reader)
\t\t\thandedOff := false
\t\t\tdefer func() {
\t\t\t\tif !handedOff {
\t\t\t\t\t_ = association.Close()
\t\t\t\t}
\t\t\t}()
\t\t\terr = socks5.WriteResponse(conn, socks5.Response{
\t\t\t\tReplyCode: socks5.ReplyCodeSuccess,
\t\t\t\tBind: M.SocksaddrFromNet(udpConn.LocalAddr()).Unwrap(),
\t\t\t})
\t\t\tif err != nil {
\t\t\t\treturn E.Cause(err, "socks5: write response")
\t\t\t}
\t\t\tif udpTimeout > 0 {
\t\t\t\t_ = udpConn.SetReadDeadline(time.Now().Add(udpTimeout))
\t\t\t}
\t\t\tfirstPacket := buf.NewPacket()
\t\t\tdestination, err := association.ReadPacket(firstPacket)
\t\t\tif err != nil {
\t\t\t\tfirstPacket.Release()
\t\t\t\treturn E.Cause(err, "socks5: read first packet")
\t\t\t}
\t\t\tvar socksPacketConn N.PacketConn = association
\t\t\tif udpTimeout > 0 {
\t\t\t\t_ = udpConn.SetReadDeadline(time.Time{})
\t\t\t\tpacketCtx, socksPacketConn = canceler.NewPacketConn(packetCtx, socksPacketConn, udpTimeout)
\t\t\t}
\t\t\tsocksPacketConn = bufio.NewCachedPacketConn(socksPacketConn, firstPacket, destination)
\t\t\t// The router owns the cached packet and onClose after this point.
\t\t\t// The control monitor closes only the lower transport and context.
\t\t\thandedOff = true
\t\t\thandler.NewPacketConnectionEx(packetCtx, socksPacketConn, source, destination, onClose)
""" + server_text[end:]
# A private build copy keeps other checkouts and cached upstream modules intact.
local = source / "fturn-sing"
added_files = {
    "protocol/socks/fturn_udp_control_test.go": ROOT / "scripts/tests/socks_udp_control_test.go",
    "protocol/socks/fturn_udp_server_test.go": ROOT / "scripts/tests/socks_udp_server_test.go",
    "protocol/socks/fturn_udp_server.go": ROOT / "scripts/patches/socks_udp_server.go",
}
files = {p.relative_to(upstream).as_posix(): p for p in upstream.rglob("*") if p.is_file()}
if local.exists():
    extras = {p.relative_to(local).as_posix() for p in local.rglob("*") if p.is_file()} - files.keys() - added_files.keys()
    if extras:
        raise SystemExit("Unexpected files in private sing build copy: " + ", ".join(sorted(extras)))
for relative, path in files.items():
    target = local / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        target.chmod(target.stat().st_mode | stat.S_IWRITE)
    shutil.copyfile(path, target)
(local / "protocol/socks/client.go").write_text(text, encoding="utf-8", newline="\n")
(local / "protocol/socks/handshake.go").write_text(server_text, encoding="utf-8", newline="\n")
for relative, path in added_files.items():
    shutil.copyfile(path, local / relative)
subprocess.run(["gofmt", "-w", "protocol/socks/handshake.go", "protocol/socks/fturn_udp_server.go", "protocol/socks/fturn_udp_server_test.go"], cwd=local, check=True)
subprocess.run(["go", "mod", "edit", "-replace=" + MODULE + "=./fturn-sing"], cwd=source, check=True)
print("sing " + VERSION + ": incoming and outgoing SOCKS UDP control monitors applied (private build copy)")