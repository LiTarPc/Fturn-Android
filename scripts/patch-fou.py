"""Install the experimental Fturn IPv4-over-UDP inbound in pinned sing-box."""
from pathlib import Path
import sys
source=Path(sys.argv[1]); patches=Path(__file__).parent/'patches'/'fou'
dest=source/'protocol'/'fturnfou';dest.mkdir(exist_ok=True)
for p in patches.glob('*.go'):
 (dest/p.name).write_bytes(p.read_bytes())
p=source/'include'/'registry.go';s=p.read_text(encoding='utf-8')
imp='"github.com/sagernet/sing-box/protocol/fturnfou"'
s=s.replace('\t'+imp+'\n','')
anchor='\t"github.com/sagernet/sing-box/protocol/direct"'
if s.count(anchor)!=1:raise SystemExit('Unexpected import registry; review FOU patch')
s=s.replace(anchor,anchor+'\n\t'+imp,1)
call='\tfturnfou.RegisterInbound(registry)'
if call not in s:
 anchor='\ttun.RegisterInbound(registry)'
 if s.count(anchor)!=1:raise SystemExit('Unexpected inbound registry; review FOU patch')
 s=s.replace(anchor,anchor+'\n'+call,1)
p.write_text(s,encoding='utf-8',newline='\n')
print('Experimental FOU IPv4 inbound installed')
