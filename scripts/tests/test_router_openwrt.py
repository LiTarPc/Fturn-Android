"""Run the real POSIX controllers against an isolated fake OpenWrt, never host networking."""
import json, os, shutil, subprocess, sys, tempfile, unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BASH = shutil.which('bash') or ('C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else '/bin/sh')
def posix(path):
    s = str(Path(path).resolve()).replace('\\', '/')
    return '/' + s[0].lower() + s[2:] if len(s) > 1 and s[1] == ':' else s

MOCK = r"""
import json,os,sys
from pathlib import Path
root=Path(os.environ['FTURN_TEST_DIR']); f=root/'mock.json';s=json.loads(f.read_text());cmd=sys.argv[1];a=sys.argv[2:]
s.setdefault('calls',[]).append([cmd]+a)
def done(text='',code=0):
 f.write_text(json.dumps(s));print(text,end='' if text.endswith('\n') or not text else '\n');sys.exit(code)
if s.get('fail_on')==[cmd]+a: done('injected failure',1)
if cmd=='id': done('0')
if cmd in ('modprobe','flock','logger','opkg','apk'):
 if cmd in ('opkg','apk') and s.get('package_failure'):done('package ABI mismatch',1)
 done()
if cmd=='readlink':
 dev=a[0].split('/class/net/')[1].split('/')[0]
 done('/drivers/'+s.get('drivers',{}).get(dev,'none'))
if cmd=='jsonfilter':
 try:d=json.load(sys.stdin)
 except ValueError:done('',1)
 e=a[a.index('-e')+1]
 if e=='@.interface[*].interface':done('\n'.join(x['interface'] for x in d['interface']))
 if e=='@.source_prefixes[*]':done('\n'.join(d['source_prefixes']))
 cur=d
 for part in e[2:].split('.'):
  if not isinstance(cur,dict) or part not in cur:done('',1)
  cur=cur[part]
 done(('true' if cur else 'false') if isinstance(cur,bool) else str(cur))
if cmd=='ubus':
 if a[:3]==['call','network.interface','dump']:done(json.dumps({'interface':[{'interface':s.get('lan_name','home')}]}))
 if a[:2]==['call','network.interface.'+s.get('lan_name','home')]:done(json.dumps({'up':True,'l3_device':s.get('lan_dev','br-home')}))
 if a[:2]==['call','network.interface.fturn_usb']:
  if a[2]=='down':s['dhcp_ready']=False;done()
  done(json.dumps({'data':{'dhcpserver':s.get('phone','192.168.42.1')}}))
 if a[:3]==['call','network','add_dynamic']:s['dhcp_ready']=True;s['dynamic']=json.loads(a[3]);done('{}')
 done('{}')
if cmd=='ip':
 joined=' '.join(a);lan=s.get('lan_dev','br-home');usb=s.get('usb','enx1234')
 if a[:3]==['-4','addr','show']:
  dev=a[-1]
  if dev==lan:done(' inet 10.23.4.1/24 scope global '+lan)
  if dev==usb and s.get('dhcp_ready',True):done(' inet 192.168.42.2/24 scope global '+usb)
  done()
 if a[:3]==['-4','route','show']:
  if '40081' in a:done('existing route' if s.get('route') or s.get('collision') else '')
  if a[-1]==lan:done('10.23.4.0/24 proto kernel scope link src 10.23.4.1')
  if a[-1]==usb and s.get('dhcp_ready',True) and not s.get('no_default'):done('default via '+s.get('phone','192.168.42.1')+' dev '+usb)
  done()
 if a==['-4','rule','show']:done('1078: existing' if s.get('collision') else '')
 if a[:2]==['fou','add'] and s.get('fou_collision'):done('already exists',1)
 if a[:2]==['link','show']:done('',0 if s.get('link') else 1)
 if a[:2]==['link','add']:s['link']=True
 if a[:2]==['link','del']:s['link']=False
 if a[:3]==['-4','route','add']:s['route']=True
 if a[:3]==['-4','route','flush']:s['route']=False
 done()
if cmd=='nft':
 if a[:2]==['list','chain']:done('comment "fturn-fou-auto-out"' if s.get('firewall') else '',0 if s.get('backend','nft')=='nft' else 1)
 if a and a[0]=='-a':done('comment "fturn-fou-auto-out" # handle 10\ncomment "fturn-fou-auto-return" # handle 11' if s.get('firewall') else '')
 if a and a[0]=='-f':s['firewall']=True;s['nft_payload']=sys.stdin.read()
 if a and a[0]=='delete':s['firewall']=False
 done()
if cmd=='iptables':
 if a[0]=='-S':done('',0 if s.get('backend')=='iptables' else 1)
 if a[0]=='-C':done('',0 if s.get('firewall') else 1)
 if a[0]=='-I':s['firewall']=True
 if a[0]=='-D':s['firewall']=False
 done()
if cmd=='nc':
 s['request']=sys.stdin.read()
 if s.get('control_fail'):done('',1)
 if s.get('unauthorized'):done('HTTP/1.1 401 Unauthorized\r\n\r\n')
 d={'protocol':1,'phone':s.get('phone','192.168.42.1'),'router':'192.168.42.2','router_port':40080,'port':40080,'mtu':1350,'lease_seconds':30,'source_prefixes':['10.23.4.0/24']}
 done('HTTP/1.1 200 OK\r\n\r\n'+json.dumps(d))
if cmd=='nslookup':done('',0 if s.get('dns_ok',True) else 1)
if cmd=='uci':
 if a==['changes','dhcp']:done('pending' if s.get('pending_uci') else '')
 if a[:2]==['-q','get']:done('dnsmasq')
 if a and a[0] in ('set','add_list','commit'):
  (root/'etc/config/dhcp').write_text('Fturn DNS installed\n')
 done()
if cmd=='service':
 if a[1] in ('running','enabled'):done('',0 if s.get(a[1],False) else 1)
 if a[1]=='start':s['running']=True
 if a[1]=='stop':s['running']=False
 if a[1]=='enable':s['enabled']=True
 if a[1]=='disable':s['enabled']=False
 done()
done('Unknown mock '+cmd+' '+str(a),1)
"""

class RouterFixture(unittest.TestCase):
    def setUp(self):
        (REPO/'.native').mkdir(exist_ok=True)
        self.tmp=tempfile.TemporaryDirectory(prefix='router-test-',dir=REPO/'.native')
        self.root=Path(self.tmp.name).resolve()
        assert self.root.is_relative_to((REPO/'.native').resolve())
        for d in ['bin','etc/fturn-router','etc/config','etc/init.d','sys/class/net/enx1234','sys/class/net/br-home/bridge','proc','var/run','var/lock','tmp']:(self.root/d).mkdir(parents=True,exist_ok=True)
        (self.root/'etc/openwrt_release').write_text('OpenWrt test')
        (self.root/'etc/fturn-router/key').write_text('ab'*32)
        (self.root/'etc/config/dhcp').write_text('Original DNS\n')
        (self.root/'sys/class/net/enx1234/ifindex').write_text('7')
        (self.root/'proc/uptime').write_text('100.00 0.00')
        self.mock={'drivers':{'enx1234':'rndis_host'},'dhcp_ready':True}
        self.save()
        script=self.root/'mock.py';script.write_text(MOCK)
        for command in ['id','ip','ubus','readlink','jsonfilter','nft','iptables','nc','nslookup','logger','modprobe','flock','uci','opkg','service']:
            wrapper=self.root/'bin'/command
            wrapper.write_bytes(('#!/bin/sh\nexec "'+posix(sys.executable)+'" "'+str(script).replace('\\','/')+'" '+command+' "$@"\n').encode());wrapper.chmod(0o755)
        timeout=self.root/'bin/timeout';timeout.write_bytes(b'#!/bin/sh\nshift\nexec "$@"\n');timeout.chmod(0o755)
        init=self.root/'etc/init.d/dnsmasq';init.write_bytes(b'#!/bin/sh\nexit 0\n');init.chmod(0o755)
        self.env=dict(os.environ,FTURN_FOU_IP=posix(self.root/'bin/ip'),FTURN_FOU_TIMEOUT=posix(self.root/'bin/timeout'),FTURN_ROOT=posix(self.root),FTURN_TEST_DIR=str(self.root),FTURN_LIB=posix(REPO/'scripts/router'),FTURN_PATH=posix(self.root/'bin')+':/usr/bin:/bin',FTURN_SERVICE_CMD=posix(self.root/'bin/service'),MSYS_NO_PATHCONV='1')
    def tearDown(self):self.tmp.cleanup()
    def save(self): (self.root/'mock.json').write_text(json.dumps(self.mock))
    def load(self):self.mock=json.loads((self.root/'mock.json').read_text());return self.mock
    def run_script(self,name,*args):
        return subprocess.run([BASH,posix(REPO/'scripts/router'/name),*map(str,args)],env=self.env,capture_output=True,text=True,timeout=30)
    def step(self):return self.run_script('fturn-fou-auto.sh','--once')
    def test_nonstandard_usb_and_lan_names_nft_and_idempotence(self):
        r=self.step();self.assertEqual(r.returncode,0,r.stderr);m=self.load()
        self.assertTrue(m['link']);self.assertIn('X-Fturn-LAN: 10.23.4.0/24',m['request']);self.assertIn('br-home',m['nft_payload'])
        count=sum(c[:3]==['ip','link','add'] for c in m['calls'])
        self.assertEqual(self.step().returncode,0)
        self.assertEqual(sum(c[:3]==['ip','link','add'] for c in self.load()['calls']),count)
    def test_fw3_iptables(self):
        self.mock['backend']='iptables';self.save();self.assertEqual(self.step().returncode,0)
        self.assertTrue(any(c[:3]==['iptables','-I','FORWARD'] for c in self.load()['calls']))
    def test_usb_dhcp_created_without_rewriting_network(self):
        self.mock.update(dhcp_ready=False,no_default=True);self.save()
        self.assertNotEqual(self.step().returncode,0);m=self.load();self.assertFalse(m['dynamic']['defaultroute']);self.assertFalse(m['dynamic']['peerdns'])
        self.assertEqual(self.step().returncode,0)
        self.assertFalse((self.root/'etc/config/network').exists())
    def test_one_control_failure_keeps_live_lease_then_expires(self):
        self.assertEqual(self.step().returncode,0);self.load();self.mock['control_fail']=True;self.save()
        self.step();self.assertTrue(self.load()['link'])
        (self.root/'proc/uptime').write_text('131.00 0.00');self.step();self.assertFalse(self.load()['link'])
    def test_dns_failure_does_not_destroy_established_tunnel(self):
        self.assertEqual(self.step().returncode,0);self.load();self.mock['dns_ok']=False;self.save()
        self.assertEqual(self.step().returncode,0);self.assertTrue(self.load()['link'])
    def test_dns_failure_on_initial_path_rolls_back(self):
        self.mock['dns_ok']=False;self.save();self.assertNotEqual(self.step().returncode,0);self.assertFalse(self.load()['link']);self.assertFalse((self.root/'var/run/fturn-router/state').exists())
    def test_unauthorized_pairing_tears_down_immediately(self):
        self.assertEqual(self.step().returncode,0);self.load();self.mock['unauthorized']=True;self.save();self.step();self.assertFalse(self.load()['link'])
    def test_unplug_removes_only_owned_rules(self):
        self.assertEqual(self.step().returncode,0);self.load();self.mock['drivers']={};self.save();self.step();m=self.load();self.assertFalse(m['link'])
        self.assertFalse(any(c[0]=='iptables' and '-F' in c for c in m['calls']))
    def test_resource_collision_is_preserved(self):
        self.mock['collision']=True;self.save();r=self.step();self.assertNotEqual(r.returncode,0)
        self.assertFalse(any(c[:4]==['ip','-4','route','flush'] for c in self.load()['calls']))
    def test_changed_usb_address_replaces_old_tunnel(self):
        self.assertEqual(self.step().returncode,0);self.load();self.mock['phone']='192.168.42.9';self.save();self.assertEqual(self.step().returncode,0)
        self.assertTrue((self.root/'var/run/fturn-router/state').read_text().startswith('192.168.42.9 '))
    def install(self):
        key=self.root/'key';key.write_text('ab'*32)
        return self.run_script('install-fturn-router.sh','--key-file',posix(key))
    def test_install_and_remote_reset_restore_dns(self):
        r=self.install();self.assertEqual(r.returncode,0,r.stderr);self.assertIn('FTURNINSTALL_OK',r.stdout)
        self.assertEqual((self.root/'etc/fturn-router/lan_network').read_text().strip(),'home')
        r=self.run_script('fturn-router','reset');self.assertEqual(r.returncode,0,r.stderr);self.assertIn('FTURNRESET_OK',r.stdout)
        self.assertEqual((self.root/'etc/config/dhcp').read_text(),'Original DNS\n');self.assertFalse((self.root/'etc/fturn-router/key').exists())
    def test_installer_failure_restores_previous_config(self):
        self.mock['fail_on']=['service','fturn-fou-auto','start'];self.save();r=self.install();self.assertNotEqual(r.returncode,0)
        self.assertEqual((self.root/'etc/config/dhcp').read_text(),'Original DNS\n');self.assertEqual((self.root/'etc/fturn-router/key').read_text(),'ab'*32)
    def test_reset_preserves_new_user_dhcp_edits(self):
        self.assertEqual(self.install().returncode,0);(self.root/'etc/config/dhcp').write_text('User changed DHCP\n')
        r=self.run_script('fturn-router','reset');self.assertNotEqual(r.returncode,0);self.assertEqual((self.root/'etc/config/dhcp').read_text(),'User changed DHCP\n')
    def test_apk_installer_and_package_failure_before_changes(self):
        opkg=self.root/'bin/opkg';apk=self.root/'bin/apk'
        apk.write_bytes(opkg.read_bytes().replace(b' opkg "$@"',b' apk "$@"'));apk.chmod(0o755);opkg.unlink()
        self.mock['package_failure']=True;self.save();r=self.install();self.assertNotEqual(r.returncode,0)
        self.assertTrue(any(c[0]=='apk' for c in self.load()['calls']))
        self.assertEqual((self.root/'etc/config/dhcp').read_text(),'Original DNS\n');self.assertFalse((self.root/'usr/sbin/fturn-router').exists())
    def test_reinstall_keeps_original_dns_backup(self):
        self.assertEqual(self.install().returncode,0)
        self.assertEqual(self.install().returncode,0)
        self.assertEqual(self.run_script('fturn-router','reset').returncode,0)
        self.assertEqual((self.root/'etc/config/dhcp').read_text(),'Original DNS\n')
    def test_first_setup_can_wait_for_usb_and_key_is_not_logged(self):
        self.mock['drivers']={};self.save();r=self.install();self.assertEqual(r.returncode,0,r.stderr)
        self.assertNotIn('ab'*32,r.stdout+r.stderr)
        self.assertNotEqual(self.step().returncode,0)
        self.assertFalse((self.root/'var/run/fturn-router/state').exists())
    def test_firewall_reload_restores_only_fturn_rules(self):
        self.assertEqual(self.step().returncode,0);self.load();self.mock['firewall']=False;self.save()
        self.assertEqual(self.step().returncode,0);self.assertTrue(self.load()['firewall'])
    def test_failed_fou_add_must_not_delete_another_services_port(self):
        self.mock['fou_collision']=True;self.save();r=self.step();self.assertNotEqual(r.returncode,0)
        self.assertFalse(any(c[:3]==['ip','fou','del'] for c in self.load()['calls']))
    def test_unbridged_lan_can_be_identified_from_ssh_connection(self):
        (self.root/'sys/class/net/br-home/bridge').rmdir()
        self.env['SSH_CONNECTION']='10.23.4.10 12345 10.23.4.1 22'
        self.assertEqual(self.step().returncode,0)
    def test_multiple_usb_devices_require_selection(self):
        (self.root/'sys/class/net/usb2').mkdir();self.mock['drivers']['usb2']='cdc_ncm';self.save()
        r=self.step();self.assertNotEqual(r.returncode,0);self.assertIn('Several USB',r.stderr)
        self.assertFalse((self.root/'var/run/fturn-router/state').exists())

if __name__=='__main__':unittest.main(verbosity=2)
