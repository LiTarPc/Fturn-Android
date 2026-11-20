"""Acceptance helpers for the separate debug app, using observed accessibility bounds."""
import re,subprocess,time,xml.etree.ElementTree as E
from pathlib import Path
PKG='com.litar.freeturn.routerip'
OUT=Path(__file__).resolve().parents[2]/'.native/openwrt-phone-acceptance'
def adb(*args):return subprocess.check_output(['adb',*map(str,args)],stderr=subprocess.STDOUT)
def tree():
 adb('shell','uiautomator','dump','/sdcard/fturn-ui.xml')
 root=E.fromstring(adb('exec-out','cat','/sdcard/fturn-ui.xml'))
 assert any(n.get('package')==PKG for n in root.iter('node')),'Test app is not visible'
 return root

def center(node):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')))
 assert x2>x1 and y2>y1
 return (x1+x2)//2,(y1+y2)//2

def click(text):
 for attempt in range(7):
  root=tree()
  for n in root.iter('node'):
   if n.get('text')==text and n.get('package')==PKG and n.get('bounds')!='[0,0][0,0]':
    adb('shell','input','tap',*center(n));return
  adb('shell','input','swipe','570','1940','570','800','350')
 raise AssertionError('Control not visible: '+text)
def fill(label,value):
 root=tree()
 field=next((n for n in root.iter('node') if n.get('class')=='android.widget.EditText' and any(c.get('text')==label for c in n.iter('node'))),None)
 assert field is not None,'Field not visible: '+label
 adb('shell','input','tap',*center(field));adb('shell','input','keyevent','123',*(['67']*64));adb('shell','input','text',value);adb('shell','input','keyevent','4')
def wait_for(fragment,timeout=75):
 start=time.monotonic()
 while time.monotonic()-start<timeout:
  texts=[n.get('text','') for n in tree().iter('node') if n.get('package')==PKG and n.get('password')!='true']
  if any(fragment in x for x in texts):return next(x for x in texts if fragment in x)
  if time.monotonic()-start>3 and time.monotonic()-start<7: adb('shell','input','swipe','570','1940','570','1510','300')
  time.sleep(.5)
 raise AssertionError('Status not seen: '+fragment)
def screenshot(name):
 (OUT/name).write_bytes(adb('exec-out','screencap','-p'))
if __name__=='__main__':
 import sys
 case=sys.argv[1]
 if case=='bad-password':
  fill('Адрес роутера','127.0.0.1');fill('Порт SSH','22441');fill('Пароль SSH','wrong-test-password');click('Настроить роутер')
  print(wait_for('Auth fail'));screenshot('bad-password.png')
 elif case=='install':
  fill('Пароль SSH','fturn-test-only');click('Настроить роутер');print(wait_for('Помощник установлен',90));screenshot('setup-success.png')
 elif case=='phone-reset':
  click('Сбросить IP-туннель в Fturn');click('Сбросить');print(wait_for('IP-туннель отключён'));screenshot('phone-reset.png')
 elif case=='router-reset':
  # Return to the SSH form after scrolling down to the phone reset button.
  for _ in range(5):adb('shell','input','swipe','570','750','570','1900','350')
  # Scroll until the password field reappears.
  for _ in range(6):
   if any(n.get('text')=='Пароль SSH' for n in tree().iter('node')):break
   adb('shell','input','swipe','570','1940','570','900','350')
  fill('Пароль SSH','fturn-test-only');click('Сбросить настройки на роутере по SSH');click('Сбросить на роутере')
  print(wait_for('Настройки Fturn на роутере сброшены'));screenshot('router-reset.png')
 elif case=='changed-key':
  fill('Пароль SSH','fturn-test-only');(OUT/'rotate-key').touch();click('Настроить роутер');print(wait_for('Отпечаток'));screenshot('changed-key.png')
