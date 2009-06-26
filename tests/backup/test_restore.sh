#!/bin/bash

function check_file
{
    data=$(adb shell cat /data/data/com.android.backuptest/files/$1)
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
echo --- Erased files
adb shell "ls -l /data/data/com.android.backuptest/files"
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
check_file file.txt "first file"
check_file another_file.txt "asdf"
check_file 3.txt "3"

echo
echo
echo
echo --- Restored files
adb shell "ls -l /data/data/com.android.backuptest/files"
echo ---
echo
