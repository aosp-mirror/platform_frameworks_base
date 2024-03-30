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

all_tests="hoststubgentest tiny-framework-dump-test hoststubgen-invoke-test ravenwood-stats-checker"

# "echo" is to remove the newlines
all_tests="$all_tests $(echo $(${0%/*}/list-ravenwood-tests.sh) )"

run() {
    echo "Running: $*"
    "${@}"
}

run ${ATEST:-atest} $all_tests
