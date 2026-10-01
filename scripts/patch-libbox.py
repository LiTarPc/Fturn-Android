"""Apply bounded compatibility fixes to the pinned sing-box 1.12.0 source."""
from pathlib import Path
import sys

source = Path(sys.argv[1]) / "transport/v2raywebsocket/client.go"
original = """\trequestURL.Path = options.Path
\terr := sHTTP.URLSetPath(&requestURL, options.Path)"""
replacement = """\t// Preserve URL query parameters from VLESS share links (FreeTurn compatibility).
\trequestPath, rawQuery, hasQuery := strings.Cut(options.Path, "?")
\trequestURL.RawQuery = rawQuery
\trequestURL.ForceQuery = hasQuery
\terr := sHTTP.URLSetPath(&requestURL, requestPath)"""
text = source.read_text()
if replacement not in text:
    if text.count(original) != 1:
        raise SystemExit("Unexpected sing-box WebSocket source; review compatibility patch")
    source.write_text(text.replace(original, replacement), encoding="utf-8", newline="\n")
print("sing-box 1.12.0: WebSocket URL query compatibility patch applied")
