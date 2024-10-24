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

# Run all the ravenwood tests + hoststubgen unit tests.
#
# Options:
#
#   -s: "Smoke" test -- skip slow tests (SysUI, ICU)

smoke=0
while getopts "s" opt; do
case "$opt" in
    s)
        smoke=1
        ;;
    '?')
        exit 1
        ;;
esac
done
shift $(($OPTIND - 1))

all_tests=(hoststubgentest tiny-framework-dump-test hoststubgen-invoke-test ravenwood-stats-checker)
all_tests+=( $(${0%/*}/list-ravenwood-tests.sh) )

# Regex to identify slow tests, in PCRE
slow_tests_re='^(SystemUiRavenTests|CtsIcuTestCasesRavenwood)$'

if (( $smoke )) ; then
    # Remove the slow tests.
    all_tests=( $(
        for t in "${all_tests[@]}"; do
            echo $t | grep -vP "$slow_tests_re"
        done
    ) )
fi

run() {
    echo "Running: $*"
    "${@}"
}

run ${ATEST:-atest} "${all_tests[@]}"
