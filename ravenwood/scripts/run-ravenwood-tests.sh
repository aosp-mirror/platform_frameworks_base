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
#
#   -x PCRE: Specify exclusion filter in PCRE
#            Example: -x '^(Cts|hoststub)' # Exclude CTS and hoststubgen tests.
#
#   -f PCRE: Specify inclusion filter in PCRE


# Regex to identify slow tests, in PCRE
SLOW_TEST_RE='^(SystemUiRavenTests|CtsIcuTestCasesRavenwood|CarSystemUIRavenTests)$'

smoke=0
include_re=""
exclude_re=""
smoke_exclude_re=""
dry_run=""
while getopts "sx:f:dtb" opt; do
case "$opt" in
    s)
        # Remove slow tests.
        smoke_exclude_re="$SLOW_TEST_RE"
        ;;
    x)
        # Take a PCRE from the arg, and use it as an exclusion filter.
        exclude_re="$OPTARG"
        ;;
    f)
        # Take a PCRE from the arg, and use it as an inclusion filter.
        include_re="$OPTARG"
        ;;
    d)
        # Dry run
        dry_run="echo"
        ;;
    t)
        # Redirect log to terminal
        export RAVENWOOD_LOG_OUT=$(tty)
        ;;
    b)
        # Build only
        ATEST=m
        ;;
    '?')
        exit 1
        ;;
esac
done
shift $(($OPTIND - 1))

all_tests=(hoststubgentest tiny-framework-dump-test hoststubgen-invoke-test ravenwood-stats-checker ravenhelpertest)
all_tests+=( $(${0%/*}/list-ravenwood-tests.sh) )

filter() {
    local re="$1"
    local grep_arg="$2"
    if [[ "$re" == "" ]] ; then
        cat # No filtering
    else
        grep $grep_arg -iP "$re"
    fi
}

filter_in() {
    filter "$1"
}

filter_out() {
    filter "$1" -v
}


# Remove the slow tests.
targets=( $(
    for t in "${all_tests[@]}"; do
        echo $t | filter_in "$include_re" | filter_out "$smoke_exclude_re" | filter_out "$exclude_re"
    done
) )

# Show the target tests

echo "Target tests:"
for t in "${targets[@]}"; do
    echo "  $t"
done

# Calculate the removed tests.

diff="$(diff  <(echo "${all_tests[@]}" | tr ' ' '\n') <(echo "${targets[@]}" | tr ' ' '\n') | grep -v [0-9] )"

if [[ "$diff" != "" ]]; then
    echo "Excluded tests:"
    echo "$diff"
fi

run() {
    echo "Running: ${@}"
    "${@}"
}

run $dry_run ${ATEST:-atest} "${targets[@]}"
