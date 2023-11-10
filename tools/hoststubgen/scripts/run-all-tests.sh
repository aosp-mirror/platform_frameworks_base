#!/bin/bash
# Copyright (C) 2023 The Android Open Source Project
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

source "${0%/*}"/../common.sh

# Move to the top directory of hoststubgen
cd ..

# These tests are known to pass.
READY_TEST_MODULES=(
  HostStubGenTest-framework-all-test-host-test
  hoststubgen-test-tiny-test
)

MUST_BUILD_MODULES=(
    run-ravenwood-test
    "${NOT_READY_TEST_MODULES[*]}"
    HostStubGenTest-framework-test
)

# First, build all the test / etc modules. This shouldn't fail.
run m "${MUST_BUILD_MODULES[@]}"

# Run the hoststubgen unittests / etc
run atest hoststubgentest hoststubgen-invoke-test

# Next, run the golden check. This should always pass too.
# The following scripts _should_ pass too, but they depend on the internal paths to soong generated
# files, and they may fail when something changes in the build system.
run ./hoststubgen/test-tiny-framework/diff-and-update-golden.sh

run ./hoststubgen/test-framework/run-test-without-atest.sh

run ./hoststubgen/test-tiny-framework/run-test-manually.sh
run atest tiny-framework-dump-test
run ./scripts/build-framework-hostside-jars-and-extract.sh

# This script is already broken on goog/master
# run ./scripts/build-framework-hostside-jars-without-genrules.sh

# These tests should all pass.
run-ravenwood-test ${READY_TEST_MODULES[*]}

echo ""${0##*/}" finished, with no unexpected failures. Ready to submit!"