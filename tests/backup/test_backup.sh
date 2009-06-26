#!/bin/bash

#adb kill-server

# set the transport
adb shell bmgr transport 1

# load up the three files
adb shell "rm /data/data/com.android.backuptest/files/* ; \
           mkdir /data/data/com.android.backuptest ; \
           mkdir /data/data/com.android.backuptest/files ; \
           echo -n first file > /data/data/com.android.backuptest/files/file.txt ; \
           echo -n asdf > /data/data/com.android.backuptest/files/another_file.txt ; \
           echo -n 3 > /data/data/com.android.backuptest/files/3.txt ; \
"

# say that the data has changed
adb shell bmgr backup com.android.backuptest

# run the backup
adb shell bmgr run
