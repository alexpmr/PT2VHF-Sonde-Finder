#!/usr/bin/env bash
# Called by .github/workflows/screenshots.yml inside the emulator runner.
set -x
PKG=com.pt2vhf.sondefinder
mkdir -p shots
adb install -r -g app/build/outputs/apk/github/debug/*.apk
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION || true
adb shell pm grant $PKG android.permission.ACCESS_COARSE_LOCATION || true
# clean status bar
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1032
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 90 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

fix() { for i in 1 2 3; do adb emu geo fix "$LON" "$LAT" 1100; sleep 1; done; }

# app settings: 25 km, last 30 days, no usage ping (keeps the site's user count clean)
prefs() { # $1 = map style, $2 = sheet collapsed
  cat > /tmp/cfg.xml <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <int name="radiusKm" value="25" />
    <int name="windowH" value="720" />
    <boolean name="usagePing" value="false" />
    <long name="lastUpdateCheck" value="9999999999999" />
    <string name="mapStyle">$1</string>
    <boolean name="sheetCollapsed" value="$2" />
</map>
XML
  adb shell am force-stop $PKG
  adb push /tmp/cfg.xml /data/local/tmp/cfg.xml
  adb shell run-as $PKG mkdir -p shared_prefs
  adb shell "run-as $PKG sh -c 'cat /data/local/tmp/cfg.xml > shared_prefs/cfg.xml'"
}

start() {
  fix
  adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
  for i in $(seq 1 8); do fix; sleep 4; done   # splash, GPS fix, SondeHub data, map tiles
}

shot() { sleep 3; adb exec-out screencap -p > "shots/$1.png"; }

# tap the centre of a view found by resource id (uiautomator dump)
tap() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  b=$(adb shell cat /sdcard/ui.xml | grep -o "resource-id=\"$PKG:id/$1\"[^>]*bounds=\"\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]\"" | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1)
  [ -z "$b" ] && { echo "view $1 not found"; return 1; }
  set -- $(echo "$b" | tr '[],' '   ')
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}

prefs dark false
start
shot 01-mapa
tap navCompass && sleep 6 && shot 02-bussola
tap navMap; sleep 2
tap btnList && shot 03-lista; adb shell input keyevent KEYCODE_BACK
tap btnSettings && shot 04-configuracoes; adb shell input keyevent KEYCODE_BACK
tap btnFilter && shot 05-filtro; adb shell input keyevent KEYCODE_BACK

prefs satellite true
start
sleep 6; shot 06-satelite

adb shell am force-stop $PKG
ls -l shots
