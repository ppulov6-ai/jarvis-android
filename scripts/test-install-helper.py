#!/usr/bin/env python3
import subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path
def adb(*a,check=True):
 r=subprocess.run(["adb",*a],text=True,capture_output=True)
 if check and r.returncode: raise RuntimeError(r.stdout+r.stderr)
 return r.stdout+r.stderr
def snapshot():
 adb("shell","uiautomator","dump","/sdcard/install-check.xml")
 s=adb("shell","cat","/sdcard/install-check.xml")
 return s,ET.fromstring(s[s.index("<?xml"):])
def texts(tree):
 return "\n".join(n.get("text","") for n in tree.iter("node"))
def click(tree,label):
 for n in tree.iter("node"):
  if n.get("text")==label and n.get("enabled")=="true":
   import re
   x1,y1,x2,y2=map(int,re.findall(r"\d+",n.get("bounds")))
   adb("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2));return True
 return False
def launch():
 adb("shell","am","start","-W","-n","ru.pulat.jarvis.installcheck/.MainActivity")
def wait(expected,name):
 deadline=time.time()+100
 while time.time()<deadline:
  s,t=snapshot();txt=texts(t)
  Path("evidence/"+name+"-ui.xml").write_text(s)
  if "Статус: "+str(expected)+" —" in txt:
   print(name+": confirmed status "+str(expected));return
  for label in ["Install","Update","Continue","Install anyway"]:
   if label in ["Continue","Install anyway"]: continue
   if click(t,label):break
  time.sleep(1)
 raise RuntimeError(name+" missing expected status; UI: "+txt)
Path("evidence").mkdir(exist_ok=True)
for p in [1,2]:
 adb("uninstall","ru.pulat.jarvis",check=False)
 adb("uninstall","ru.pulat.jarvis.installcheck",check=False)
 assert "Success" in adb("install","--no-streaming","diagnostic-apk/Jarvis-install-check.apk")
 launch();s,t=snapshot()
 assert click(t,"Установить новую версию")
 time.sleep(1);s,t=snapshot()
 assert "Allow from this source" in texts(t),texts(t)
 Path("evidence/permission-"+str(p)+".xml").write_text(s)
 adb("shell","input","keyevent","4")
 adb("shell","appops","set","ru.pulat.jarvis.installcheck","REQUEST_INSTALL_PACKAGES","allow")
 launch();s,t=snapshot()
 assert click(t,"Установить новую версию")
 wait(0,"clean-"+str(p))
 s,t=snapshot();assert click(t,"Отправить отчёт")
 time.sleep(1)
 Path("evidence/share-"+str(p)+".xml").write_text(snapshot()[0])
 adb("shell","input","keyevent","4")
 adb("uninstall","ru.pulat.jarvis")
 assert "Success" in adb("install","--no-streaming","old.apk")
 launch();s,t=snapshot()
 assert click(t,"Установить новую версию")
 wait(4,"conflict-"+str(p))
print("Two clean installs, two signing conflicts, permission and report paths verified")
