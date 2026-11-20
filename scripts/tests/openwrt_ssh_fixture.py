"""Loopback-only SSH acceptance fixture. Execute only exact APK-bundled setup payloads.
The OpenWrt network/package commands are isolated mocks; this is not a kernel FOU emulator.
"""
import argparse, hashlib, importlib.util, json, os, re, socket, subprocess, threading, time, zipfile
from pathlib import Path
import paramiko
from test_router_openwrt import RouterFixture, BASH, REPO, posix

parser=argparse.ArgumentParser();parser.add_argument('--port',type=int,default=22441);args=parser.parse_args()
out=REPO/'.native/openwrt-phone-acceptance';out.mkdir(parents=True,exist_ok=True)
fixture=RouterFixture();fixture.setUp();root=fixture.root
fixture.env['FTURN_LIB']=posix(root/'usr/lib/fturn-router')
assets={}
with zipfile.ZipFile(REPO/'dist/fturn-openwrt-usb-auto-debug.apk') as apk:
 for name in ['fturn-fou-auto.sh','fturn-fou-auto.init','fturn-router-common.sh','fturn-router','install-fturn-router.sh']:
  assets[name]=apk.read('assets/router-setup/'+name).decode().replace('\r\n','\n').rstrip()
keyfile=out/'fixture-host-key';key=paramiko.RSAKey.from_private_key_file(str(keyfile)) if keyfile.exists() else paramiko.RSAKey.generate(2048);key.write_private_key_file(str(keyfile))
audit=json.loads((out/'ssh-audit.json').read_text()) if (out/'ssh-audit.json').exists() else [];lock=threading.Lock()
def record(event_name,**data):
 with lock:
  event={'event':event_name,**data};audit.append(event);(out/'ssh-audit.json').write_text(json.dumps(audit,indent=2));print(json.dumps(event),flush=True)
def validate_install(script):
 prefix='set -eu\numask 077\nwork=$(mktemp -d /tmp/fturn-setup.XXXXXX)\ntrap \'rm -rf "$work"\' EXIT\n'
 if not script.startswith(prefix):return False
 remaining=script[len(prefix):];expected=prefix
 for name,text in assets.items():
  m=re.match(r'cat > "\$work/'+re.escape(name)+r'" <<\'(FTURN_ASSET_[a-f0-9]{32})\'\n',remaining)
  if not m:return False
  block=m.group(0)+text+'\n'+m.group(1)+'\n'
  if not remaining.startswith(block):return False
  remaining=remaining[len(block):];expected+=block
 return re.fullmatch(r'printf \'%s\\n\' \'[a-fA-F0-9]{64}\' > "\$work/key"\nsh "\$work/install-fturn-router.sh" --key-file "\$work/key"\n',remaining) is not None
class Server(paramiko.ServerInterface):
 def __init__(self):self.event=threading.Event();self.command=None
 def get_allowed_auths(self,username):return 'password'
 def check_auth_password(self,username,password):
  ok=username=='root' and password=='fturn-test-only';record('auth',accepted=ok)
  return paramiko.AUTH_SUCCESSFUL if ok else paramiko.AUTH_FAILED
 def check_channel_request(self,kind,chanid):return paramiko.OPEN_SUCCEEDED if kind=='session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
 def check_channel_exec_request(self,channel,command):self.command=command.decode();self.event.set();return True

def serve(conn,hostkey):
 transport=paramiko.Transport(conn);transport.add_server_key(hostkey);server=Server()
 try:
  transport.start_server(server=server);channel=transport.accept(15)
  if channel is None or not server.event.wait(15):return
  command=server.command
  if command=='exec 2>&1\ntest "$(id -u)" = 0 && test -r /etc/openwrt_release && echo FTURN_SSH_OK':
   record('probe');channel.sendall(b'FTURN_SSH_OK\n');channel.send_exit_status(0)
  elif command=='exec 2>&1\nsh -s':
   channel.settimeout(20);data=bytearray()
   while True:
    chunk=channel.recv(32768)
    if not chunk:break
    data.extend(chunk)
    if len(data)>1_000_000:raise ValueError('oversized fixture payload')
   script=data.decode();reset=script=='test -x /usr/sbin/fturn-router || { echo FTURN_HELPER_MISSING; exit 1; }\n/usr/sbin/fturn-router reset\n'
   if not reset and not validate_install(script):raise ValueError('payload differs from bundled APK assets')
   if reset:script=script.replace('/usr/sbin/fturn-router',posix(root/'usr/sbin/fturn-router'))
   else:script=script.replace('/tmp/fturn-setup.XXXXXX',posix(root/'tmp/fturn-setup.XXXXXX'))
   # The only recursive cleanup is the verified mktemp directory inside this workspace fixture.
   assert root.resolve().is_relative_to((REPO/'.native').resolve())
   r=subprocess.run([BASH,'-s'],input=script,env=fixture.env,capture_output=True,text=True,timeout=90)
   m=fixture.load();summary={'kind':'reset' if reset else 'install','code':r.returncode,'dns_original':(root/'etc/config/dhcp').read_text()=='Original DNS\n','helper_installed':(root/'usr/sbin/fturn-router').exists(),'pairing_present':(root/'etc/fturn-router/key').exists(),'service_enabled':m.get('enabled',False)}
   record('execution',**summary);channel.sendall((r.stdout+r.stderr).encode());channel.send_exit_status(r.returncode)
  else:raise ValueError('fixture denies arbitrary commands')
  channel.shutdown_write();time.sleep(.2)
 except Exception as e:record('connection_error',type=type(e).__name__,message=str(e)[:200])
 finally:transport.close()
listener=socket.socket();listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);listener.bind(('127.0.0.1',args.port));listener.listen(8);listener.settimeout(1)
record('ready',port=args.port)
try:
 while not (out/'stop-ssh-fixture').exists():
  try:conn,_=listener.accept()
  except socket.timeout:continue
  if (out/'rotate-key').exists():
   key=paramiko.RSAKey.generate(2048);(out/'rotate-key').unlink();record('key_rotated')
  threading.Thread(target=serve,args=(conn,key),daemon=True).start()
finally:
 listener.close();fixture.tearDown();record('stopped')
