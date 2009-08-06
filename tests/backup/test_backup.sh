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

#FIXME: what was this for?
#adb kill-server

b_pkgs=$(adb $ADB_OPTS shell dumpsys backup | \
         ruby -ne 'print($1+" ") if $_ =~ /^\s*ApplicationInfo\{\S+ (.+?)\}/')

# wipe prior backup data for packages, including the metadata package @pm@
for pkg in $b_pkgs '@pm@'; do
    adb $ADB_OPTS shell bmgr wipe "$pkg"
done

# who knows?
echo 'Waiting 5 seconds for things to settle...'
sleep 5

# run adb as root so we can poke at com.android.backuptest's data
root_status=$(adb $ADB_OPTS root)
if [ "x$root_status" != "xadbd is already running as root" ]; then
    sleep 2
    adb $ADB_OPTS 'wait-for-device'
fi

# show commands as we go
set -x

# set the transport
adb $ADB_OPTS shell bmgr transport com.google.android.backup/.BackupTransportService

# load up the three files
adb $ADB_OPTS shell \
   "rm /data/data/com.android.backuptest/files/file.txt ; \
    rm /data/data/com.android.backuptest/files/another_file.txt ; \
    rm /data/data/com.android.backuptest/files/empty.txt ; \
    mkdir /data/data/com.android.backuptest ; \
    mkdir /data/data/com.android.backuptest/files ; \
    mkdir /data/data/com.android.backuptest/shared_prefs ; \
    echo -n \"<map><int name=\\\"pref\\\" value=\\\"1\\\" /></map>\" \
            > /data/data/com.android.backuptest/shared_prefs/raw.xml ; \
    echo -n first file > /data/data/com.android.backuptest/files/file.txt ; \
    echo -n asdf > /data/data/com.android.backuptest/files/another_file.txt ; \
    echo -n "" > /data/data/com.android.backuptest/files/empty.txt ; \
    date >> /data/data/com.android.backuptest/files/3.txt ; \
"
#    echo -n 3 > /data/data/com.android.backuptest/files/3.txt ; \

# say that the data has changed
adb $ADB_OPTS shell bmgr backup com.android.backuptest

# run the backup
adb $ADB_OPTS shell bmgr run

