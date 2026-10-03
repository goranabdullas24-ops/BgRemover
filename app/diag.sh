#!/bin/bash
set -x
mkdir -p diag
APK=$(ls app/build/outputs/apk/release/*.apk | head -1)
adb install -r "$APK"
adb logcat -c
adb shell monkey -p krd.bgremover -c android.intent.category.LAUNCHER 1
sleep 15
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml diag/$1.xml >/dev/null; }
center() { python3 - "$1" "$2" "$3" <<'PY'
import re,sys
x=open(f"diag/{sys.argv[1]}.xml",encoding="utf-8").read()
for m in re.finditer(r'<node [^>]*>',x):
    n=m.group(0)
    if (sys.argv[2]=="edit" and 'class="android.widget.EditText"' in n) or (sys.argv[2]=="text" and f'text="{sys.argv[3]}"' in n):
        a=list(map(int,re.findall(r'\d+',re.search(r'bounds="([^"]*)"',n)[1])))
        print((a[0]+a[2])//2,(a[1]+a[3])//2); break
PY
}
dump ui1
E=$(center ui1 edit x); S=$(center ui1 text "گەڕان و داگرتنی وێنە")
adb shell input tap $E; sleep 2
adb shell input text "white%stiger"; sleep 1
adb shell input keyevent 111; sleep 1
adb shell input tap $S
sleep 25
adb exec-out screencap -p > diag/2_results.png
dump ui2
adb shell input swipe 160 500 160 250 400; sleep 2; dump ui2; adb exec-out screencap -p > diag/2b.png
U=$(center ui2 text "2×"); echo "2x at $U"
adb shell input tap $U
for i in $(seq 1 11); do sleep 50; adb exec-out screencap -p > diag/3_up_$i.png; done
dump ui3
adb logcat -d > diag/logcat.txt
grep -E "krd.bgremover|AndroidRuntime|FATAL|System.err" diag/logcat.txt | tail -200 > diag/logcat_short.txt
true
