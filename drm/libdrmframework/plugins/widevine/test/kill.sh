#!/bin/sh
PID=`adb shell ps | grep test-wvdrmplugin | awk '{print $2}'`
adb shell kill -9 $PID
