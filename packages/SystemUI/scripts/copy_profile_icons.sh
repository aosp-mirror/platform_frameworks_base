#!/bin/bash

for user in `adb $* shell ls /data/system/users | grep -v xml`
do
  user=${user/$'\r'/}
  adb shell mkdir /data/user/${user}/users
  for photo in `adb $* shell ls /data/system/users | grep -v xml`
  do
    photo=${photo/$'\r'/}
    adb shell mkdir /data/user/${user}/users/${photo}
    adb pull /data/system/users/${photo}/photo.png
    adb push photo.png /data/user/${user}/users/${photo}
  done
done
