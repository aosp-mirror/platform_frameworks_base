#!/bin/sh

# Copyright 2015 Google Inc.
#
# Use of this source code is governed by a BSD-style license that can be
# found in the LICENSE file.
#
# Before this can be used, the device must be rooted and the filesystem must be writable by Skia
# - These steps are necessary once after flashing to enable capture -
# adb root
# adb remount
# adb reboot

if [ -z "$1" ]; then
    printf 'Usage:\n    skp-capture.sh PACKAGE_NAME OPTIONAL_FRAME_COUNT\n\n'
    printf "Use \`adb shell 'pm list packages'\` to get a listing.\n\n"
    exit 1
fi
if ! command -v adb > /dev/null 2>&1; then
    if [ -x "${ANDROID_SDK_ROOT}/platform-tools/adb" ]; then
        adb() {
            "${ANDROID_SDK_ROOT}/platform-tools/adb" "$@"
        }
    else
        echo 'adb missing'
        exit 2
    fi
fi
phase1_timeout_seconds=60
phase2_timeout_seconds=300
package="$1"
extension="skp"
if (( "$2" > 1 )); then # 2nd arg is number of frames
    extension="mskp" # use different extension for multi frame files.
fi
filename="$(date '+%H%M%S').${extension}"
remote_path="/data/data/${package}/cache/${filename}"
local_path_prefix="$(date '+%Y-%m-%d_%H%M%S')_${package}"
local_path="${local_path_prefix}.${extension}"
enable_capture_key='debug.hwui.capture_skp_enabled'
enable_capture_value=$(adb shell "getprop '${enable_capture_key}'")

# TODO(nifong): check if filesystem is writable here with "avbctl get-verity"
# result will either start with "verity is disabled" or "verity is enabled"

if [ -z "$enable_capture_value" ]; then
    printf 'debug.hwui.capture_skp_enabled was found to be disabled, enabling it now.\n'
    printf " restart the process you want to capture on the device, then retry this script.\n\n"
    adb shell "setprop '${enable_capture_key}' true"
    exit 1
fi
if [ ! -z "$2" ]; then
    adb shell "setprop 'debug.hwui.capture_skp_frames' $2"
fi
filename_key='debug.hwui.skp_filename'
adb shell "setprop '${filename_key}' '${remote_path}'"
spin() {
    case "$spin" in
         1) printf '\b|';;
         2) printf '\b\\';;
         3) printf '\b-';;
         *) printf '\b/';;
    esac
    spin=$(( ( ${spin:-0} + 1 ) % 4 ))
    sleep $1
}

banner() {
    printf '\n=====================\n'
    printf '   %s' "$*"
    printf '\n=====================\n'
}
banner '...WAITING FOR APP INTERACTION...'
# Waiting for nonzero file is an indication that the pipeline has both opened the file and written
# the header. With multiple frames this does not occur until the last frame has been recorded,
# so we continue to show the "waiting for app interaction" message as long as the app still requires
# interaction to draw more frames.
adb_test_file_nonzero() {
    # grab first byte of `du` output
    X="$(adb shell "du \"$1\" 2> /dev/null | dd bs=1 count=1 2> /dev/null")"
    test "$X" && test "$X" -ne 0
}
timeout=$(( $(date +%s) + $phase1_timeout_seconds))
while ! adb_test_file_nonzero "$remote_path"; do
    spin 0.05
    if [ $(date +%s) -gt $timeout ] ; then
        printf '\bTimed out.\n'
        adb shell "setprop '${filename_key}' ''"
        exit 3
    fi
done
printf '\b'

# Disable further capturing
adb shell "setprop '${filename_key}' ''"

banner '...SAVING...'
# return the size of a file in bytes
adb_filesize() {
    adb shell "wc -c \"$1\"" 2> /dev/null | awk '{print $1}'
}
timeout=$(( $(date +%s) + $phase2_timeout_seconds))
last_size='0' # output of last size check command
unstable=true # false once the file size stops changing
counter=0 # used to perform size check only 1/sec though we update spinner 20/sec
# loop until the file size is unchanged for 1 second.
while [ $unstable != 0 ] ; do
    spin 0.05
    counter=$(( $counter+1 ))
    if ! (( $counter % 20)) ; then
        new_size=$(adb_filesize "$remote_path")
        unstable=$(($(adb_filesize "$remote_path") != last_size))
        last_size=$new_size
    fi
    if [ $(date +%s) -gt $timeout ] ; then
        printf '\bTimed out.\n'
        adb shell "setprop '${filename_key}' ''"
        exit 3
    fi
done
printf '\b'

printf "SKP file serialized: %s\n" $(echo $last_size | numfmt --to=iec)

i=0; while [ $i -lt 10 ]; do spin 0.10; i=$(($i + 1)); done; echo

adb pull "$remote_path" "$local_path"
if ! [ -f "$local_path" ] ; then
    printf "something went wrong with `adb pull`."
    exit 4
fi
adb shell rm "$remote_path"
printf '\nSKP saved to file:\n    %s\n\n'  "$local_path"

