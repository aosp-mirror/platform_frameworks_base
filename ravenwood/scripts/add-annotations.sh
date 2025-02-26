#!/bin/bash
# Copyright (C) 2025 The Android Open Source Project
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

#
# Use "ravehleper mm" to create a shell script which:
# - Reads read a list of methods from STDIN
#   Which basically looks like a list of 'com.android.ravenwoodtest.tests.Test1#testA'
# - Add @DisabledOnRavenwood to them
#
# Example usage:
#
# ./add-annotations.sh $ANDROID_BUILD_TOP/frameworks/base/ravenwood/tests <METHOD-LIST.txt
#
# Use a different annotation instead. (Note, in order to use an at, you need to use a double-at.)
# ./add-annotations.sh -t '@@Ignore' $ANDROID_BUILD_TOP/frameworks/base/ravenwood/tests <METHOD-LIST.txt
#

set -e

# Uncomment it to always build ravenhelper (slow)
# ${BUILD_CMD:-m} ravenhelper

# We add this line to each methods found.
# Note, if we used a single @, that'd be handled as an at file. Use
# the double-at instead.
annotation="@@android.platform.test.annotations.DisabledOnRavenwood(reason = \"bulk-disabled by script\")"
while getopts "t:" opt; do
case "$opt" in
    t)
        annotation="$OPTARG"
        ;;
    '?')
        exit 1
        ;;
esac
done
shift $(($OPTIND - 1))

source_dirs="$@"

OUT_SCRIPT="${OUT_SCRIPT:-/tmp/add-annotations.sh}"

rm -f "$OUT_SCRIPT"


with_flag() {
    local flag="$1"
    shift

    for arg in "$@"; do
        echo "$flag $arg"
    done
}

run() {
    echo "Running: $*"
    "$@"
}

run ${RAVENHELPER_CMD:-ravenhelper mm} \
    --output-script $OUT_SCRIPT \
    --text "$annotation" \
    $(with_flag --src $source_dirs)


if ! [[ -f $OUT_SCRIPT ]] ; then
    # no operations generated.
    exit 0
fi

echo
echo "Created script at $OUT_SCRIPT. Run it with: sh $OUT_SCRIPT"
