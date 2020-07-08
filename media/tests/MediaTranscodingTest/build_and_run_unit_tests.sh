#!/bin/bash
#
# Run tests in this directory.
#

if [ -z "$ANDROID_BUILD_TOP" ]; then
    echo "Android build environment not set"
    exit -1
fi

# ensure we have mm
. $ANDROID_BUILD_TOP/build/envsetup.sh

mm

echo "[==========] waiting for device"
adb root && adb wait-for-device remount

echo "[==========] set to use real transcoder"
adb shell setprop debug.transcoding.simulated_transcoder false;
adb shell kill -9 `pid media.transcoding`

echo "[==========] build test apk"
mmm -j16 .
adb install -r -g ${OUT}/testcases/mediatranscodingtest/arm64/mediatranscodingtest.apk

# Push the files into app's cache directory/
FILES=$ANDROID_BUILD_TOP/frameworks/av/media/libmediatranscoding/tests/assets/*
for file in $FILES
do
adb push --sync $file /data/user/0/com.android.mediatranscodingtest/cache/
done

echo "[==========] running real transcoding tests"
adb shell am instrument -e class com.android.mediatranscodingtest.MediaTranscodeManagerTest -w com.android.mediatranscodingtest/.MediaTranscodingTestRunner

