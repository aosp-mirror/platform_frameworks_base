#!/bin/bash

function check_file
{
    data=$(adb shell cat /data/data/com.android.backuptest/$1)
    if [ "$data" = "$2" ] ; then
        echo "$1 has correct value [$2]"
    else
        echo $1 is INCORRECT
        echo "   value:    [$data]"
        echo "   expected: [$2]"
    fi
}

# delete the old data
echo --- Previous files
adb shell "ls -l /data/data/com.android.backuptest/files"
adb shell "rm /data/data/com.android.backuptest/files/*"
echo --- Previous shared_prefs
adb shell "ls -l /data/data/com.android.backuptest/shared_prefs"
adb shell "rm /data/data/com.android.backuptest/shared_prefs/*"
echo --- Erased files and shared_prefs
adb shell "ls -l /data/data/com.android.backuptest/files"
adb shell "ls -l /data/data/com.android.backuptest/shared_prefs"
echo ---

echo
echo
echo

# run the restore
adb shell bmgr restore 0

echo
echo
echo

# check the results
check_file files/file.txt "first file"
check_file files/another_file.txt "asdf"
check_file files/3.txt "3"
check_file files/empty.txt ""
check_file shared_prefs/raw.xml '<map><int name="pref" value="1" /></map>'

echo
echo
echo
echo --- Restored files
adb shell "ls -l /data/data/com.android.backuptest/files"
echo --- Restored shared_prefs
adb shell "ls -l /data/data/com.android.backuptest/shared_prefs"
echo ---
echo
