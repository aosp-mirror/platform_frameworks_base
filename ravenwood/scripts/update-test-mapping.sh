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

# Update f/b/r/TEST_MAPPING with all the ravenwood tests as presubmit.
#
# Note, before running it, make sure module-info.json is up-to-date by running
# (any) build.

set -e

# Tests that shouldn't be in presubmit.
EXEMPT='^(SystemUiRavenTests)$'

main() {
    local script_name="${0##*/}"
    local script_dir="${0%/*}"
    local test_mapping="$script_dir/../TEST_MAPPING"
    local test_mapping_bak="$script_dir/../TEST_MAPPING.bak"

    local header="$(sed -ne '1,/AUTO-GENERATED-START/p' "$test_mapping")"
    local footer="$(sed -ne '/AUTO-GENERATED-END/,$p' "$test_mapping")"

    echo "Getting all tests"
    local tests=( $("$script_dir/list-ravenwood-tests.sh" | grep -vP "$EXEMPT") )

    local num_tests="${#tests[@]}"

    if (( $num_tests == 0 )) ; then
        echo "Something went wrong. No ravenwood tests detected." 1>&2
        return 1
    fi

    echo "Tests: ${tests[@]}"

    echo "Creating backup at $test_mapping_bak"
    cp "$test_mapping" "$test_mapping_bak"

    echo "Updating $test_mapping"
    {
        echo "$header"

        echo "    // DO NOT MODIFY MANUALLY"
        echo "    // Use scripts/$script_name to update it."

        local i=0
        while (( $i < $num_tests )) ; do
            local comma=","
            if (( $i == ($num_tests - 1) )); then
                comma=""
            fi
            echo "    {"
            echo "      \"name\": \"${tests[$i]}\","
            echo "      \"host\": true"
            echo "    }$comma"

            i=$(( $i + 1 ))
        done

        echo "$footer"
    } >"$test_mapping"

    if cmp "$test_mapping_bak" "$test_mapping" ; then
        echo "No change detecetd."
        return 0
    fi
    echo "Updated $test_mapping"

    # `|| true` is needed because of `set -e`.
    diff -u "$test_mapping_bak" "$test_mapping" || true
    return 0
}

main
