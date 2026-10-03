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
B=$(python3 - <<'PY'
import re
x=open("diag/ui1.xml",encoding="utf-8").read()
m=re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"',x)
print((int(m[1])+int(m[3]))//2,(int(m[2])+int(m[4]))//2) if m else print("540 300")
PY
)
adb shell input tap $B
sleep 1
adb shell input text "Lionel%sMessi"
adb shell input keyevent 66
sleep 25
adb exec-out screencap -p > diag/2_search.png
adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml diag/ui2.xml
adb logcat -d > diag/logcat.txt
grep -iE "bgremover|AndroidRuntime|okhttp|Exception" diag/logcat.txt | tail -200 > diag/logcat_short.txt
true
