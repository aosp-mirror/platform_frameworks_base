#!/bin/bash
#
# Run samples from this directory
#

if [ -z "$ANDROID_BUILD_TOP" ]; then
    echo "Android build environment not set"
    exit -1
fi

# ensure we have mm
. $ANDROID_BUILD_TOP/build/envsetup.sh

mm

echo "waiting for device"

adb root && adb wait-for-device remount

echo "========================================"
echo "testing soundpool_stress"
uidir="/product/media/audio/notifications"
adb push $OUT/system/bin/soundpool_stress /system/bin

# test SoundPool playback of all the UI sound samples (loaded twice) looping 10s 1 thread.
adb shell /system/bin/soundpool_stress -l -1 $uidir/*.ogg $uidir/*.ogg

# test SoundPool playback of all the UI sound samples (repeating 3 times) looping 10s 1 thread.
adb shell /system/bin/soundpool_stress -l 1 -r 3 $uidir/*.ogg

# performance test SoundPool playback of all the UI sound samples (x2)
# 1 iterations, looping, 1 second playback, 4 threads.
adb shell /system/bin/soundpool_stress -i 1 -l -1 -p 1 -t 4 $uidir/*.ogg $uidir/*.ogg
