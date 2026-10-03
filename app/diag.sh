#!/bin/bash
set -x
mkdir -p diag
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
adb install -r "$APK"
adb logcat -c
adb shell monkey -p krd.bgremover -c android.intent.category.LAUNCHER 1
sleep 15
adb exec-out screencap -p > diag/1_start.png
adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml diag/ui1.xml
center() { python3 - "$1" "$2" <<'PY'
import re,sys
x=open("diag/ui1.xml",encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if (sys.argv[1]=="edit" and 'class="android.widget.EditText"' in n) or (sys.argv[1]=="text" and f'text="{sys.argv[2]}"' in n):
        a=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]*)"',n)[1])))
        print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
}
E=$(center edit x); S=$(center text "گەڕان و داگرتنی وێنە")
echo "edit=$E search=$S"
adb shell input tap $E
sleep 2
adb shell input text "Messi"
sleep 1
adb exec-out screencap -p > diag/2_typed.png
adb shell input keyevent 111   # hide keyboard (ESC)
sleep 1
adb shell input tap $S
for i in 1 2 3 4; do sleep 8; adb exec-out screencap -p > diag/3_search_$i.png; done
adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml diag/ui2.xml
adb logcat -d > diag/logcat.txt
grep -E " krd.bgremover|AndroidRuntime: (FATAL|Process|java)|System.err" diag/logcat.txt | tail -200 > diag/logcat_short.txt
true
