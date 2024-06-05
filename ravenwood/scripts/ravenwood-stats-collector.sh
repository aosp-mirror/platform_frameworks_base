#!/bin/bash
# Copyright (C) 2024 The Android Open Source Project
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

# Script to collect the ravenwood "stats" CVS files and create a single file.

set -e

# Output files
out_dir=/tmp/ravenwood
stats=$out_dir/ravenwood-stats-all.csv
apis=$out_dir/ravenwood-apis-all.csv
keep_all_dir=$out_dir/ravenwood-keep-all/

rm -fr $out_dir
mkdir -p $out_dir
mkdir -p $keep_all_dir

# Where the input files are.
path=$ANDROID_BUILD_TOP/out/host/linux-x86/testcases/ravenwood-stats-checker/x86_64/

timestamp="$(date --iso-8601=seconds)"

m() {
    ${ANDROID_BUILD_TOP}/build/soong/soong_ui.bash --make-mode "$@"
}

# Building this will generate the files we need.
m ravenwood-stats-checker

# Start...

cd $path

dump() {
    local jar=$1
    local file=$2

    # Remove the header row, and prepend the columns.
    sed -e '1d' -e "s/^/$jar,$timestamp,/" $file
}

collect_stats() {
    local out="$1"
    {
        # Copy the header, with the first column appended.
        echo -n "Jar,Generated Date,"
        head -n 1 hoststubgen_framework-minus-apex_stats.csv

        dump "framework-minus-apex" hoststubgen_framework-minus-apex_stats.csv
        dump "service.core"  hoststubgen_services.core_stats.csv
    } > "$out"

    echo "Stats CVS created at $out"
}

collect_apis() {
    local out="$1"
    {
        # Copy the header, with the first column appended.
        echo -n "Jar,Generated Date,"
        head -n 1 hoststubgen_framework-minus-apex_apis.csv

        dump "framework-minus-apex"  hoststubgen_framework-minus-apex_apis.csv
        dump "service.core"  hoststubgen_services.core_apis.csv
    } > "$out"

    echo "API CVS created at $out"
}


collect_stats $stats
collect_apis $apis

cp *keep_all.txt $keep_all_dir
echo "Keep all files created at:"
find $keep_all_dir -type f