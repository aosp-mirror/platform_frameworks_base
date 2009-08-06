#!/bin/bash

# Copyright (C) 2009 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


ADB_OPTS="$@"

function check_file
{
    data=$(adb $ADB_OPTS shell cat /data/data/com.android.backuptest/$1)
    if [ "$data" = "$2" ] ; then
        echo "$1 has correct value [$2]"
    else
        echo $1 is INCORRECT
        echo "   value:    [$data]"
        echo "   expected: [$2]"
    fi
}

# run adb as root so we can poke at com.android.backuptest's data
root_status=$(adb $ADB_OPTS root)
if [ "x$root_status" != "xadbd is already running as root" ]; then
    echo -n "Restarting adb as root..."
    sleep 2
    adb $ADB_OPTS 'wait-for-device'
    echo done.
fi

# delete the old data
echo --- Previous files
adb $ADB_OPTS shell "ls -l /data/data/com.android.backuptest/files"
adb $ADB_OPTS shell "rm /data/data/com.android.backuptest/files/*"
echo --- Previous shared_prefs
adb $ADB_OPTS shell "ls -l /data/data/com.android.backuptest/shared_prefs"
adb $ADB_OPTS shell "rm /data/data/com.android.backuptest/shared_prefs/*"
echo --- Erased files and shared_prefs
adb $ADB_OPTS shell "ls -l /data/data/com.android.backuptest/files"
adb $ADB_OPTS shell "ls -l /data/data/com.android.backuptest/shared_prefs"
echo ---

echo
echo
echo

# FIXME: there's probably a smarter way to do this
# FIXME: if we can get the android ID, that's probably the safest thing to do
# pick the most recent set and restore from it
restore_set=$(adb $ADB_OPTS shell bmgr list sets | head -n1 | awk '{print $1}')

# run the restore
printf "Restoring from set %d (hex: 0x%x)\n" $restore_set $restore_set
adb $ADB_OPTS shell bmgr restore $restore_set

echo
echo
echo

# check the results
check_file files/file.txt "first file"
check_file files/another_file.txt "asdf"
#check_file files/3.txt "3"
check_file files/empty.txt ""
check_file shared_prefs/raw.xml '<map><int name="pref" value="1" /></map>'

echo
echo
echo
echo --- Restored files
adb $ADB_OPTS shell "ls -l /data/data/com.android.backuptest/files"
echo --- Restored shared_prefs
adb $ADB_OPTS shell "ls -l /data/data/com.android.backuptest/shared_prefs"
echo ---
echo

echo "Last 3 timestamps in 3.txt:"
adb $ADB_OPTS shell cat /data/data/com.android.backuptest/files/3.txt | tail -n 3

