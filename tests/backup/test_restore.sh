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

# uncomment for debugging
#export DRY_RUN="echo"
source test_backup_common.sh

[ -z "$BUGREPORT_DIR" ] && BUGREPORT_DIR="$HOME/backup/bugreports"

function check_file
{
    data=$(a shell cat /data/data/com.android.backuptest/$1)
    if [ "$data" = "$2" ] ; then
        echo "$1 has correct value [$2]"
        return 0
    else
        echo $1 is INCORRECT
        echo "   value:    [$data]"
        echo "   expected: [$2]"
        return 1
    fi
}

function check_exists
{
    # return 0 if file exists, 1 otherwise
    data=$(a shell "ls $@ 2> /dev/null >/dev/null && echo -n exists")
    if [ "$data" = "exists" ]; then
        return 0
    else
        return 1
    fi
}

# Make sure adb is root so we can poke at com.android.backuptest's data
adb_root

# delete the old data
echo --- Previous files
a shell "ls -l /data/data/com.android.backuptest/files"
a shell "rm /data/data/com.android.backuptest/files/*"
echo --- Previous shared_prefs
a shell "ls -l /data/data/com.android.backuptest/shared_prefs"
a shell "rm /data/data/com.android.backuptest/shared_prefs/*"
echo --- Erased files and shared_prefs
a shell "ls -l /data/data/com.android.backuptest/files"
a shell "ls -l /data/data/com.android.backuptest/shared_prefs"
echo ---

echo
echo

# FIXME: there's probably a smarter way to do this
# FIXME: if we can get the android ID, that's probably the safest thing to do
# pick the most recent set and restore from it
restore_set=$(a shell bmgr list sets | head -n1 | awk '{print $1}')

# run the restore
echo "Restoring from set [$restore_set]"
a shell bmgr restore "$restore_set"

echo
echo

# check the results
export need_bug=0

# make sure files have the expected contents
check_file files/file.txt "first file" || need_bug=1
check_file files/another_file.txt "asdf" || need_bug=1
#check_file files/3.txt "3" || need_bug=1
check_file files/empty.txt "" || need_bug=1
check_file shared_prefs/raw.xml '<map><int name="pref" value="1" /></map>' || need_bug=1

# make sure that missing files weren't somehow created
check_exists files/file_doesnt_exist.txt && need_bug=1
check_exists files/no_files_here.txt && need_bug=1

if [ \( "$need_bug" -ne 0 \) -a -d "$BUGREPORT_DIR" ]; then
    dev_id=$(a get-serialno)
    filename="${dev_id}_`date +%s`"
    echo "Grabbing bugreport; filename is $filename"
    a bugreport > "$BUGREPORT_DIR/$filename.txt"
fi

echo
echo --- Restored files
a shell "ls -l /data/data/com.android.backuptest/files"
echo --- Restored shared_prefs
a shell "ls -l /data/data/com.android.backuptest/shared_prefs"
echo ---
echo

echo "Last 3 timestamps in 3.txt:"
a shell cat /data/data/com.android.backuptest/files/3.txt | tail -n 3

exit $need_bug

