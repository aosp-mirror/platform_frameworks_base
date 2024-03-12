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


# Script to build `framework-host-stub` and `framework-host-impl`, and copy the
# generated jars to $out, and unzip them. Useful for looking into the generated files.

source "${0%/*}"/../common.sh

out=framework-all-stub-out

rm -fr $out
mkdir -p $out

# Build the jars with `m`.
run m framework-all-hidden-api-host

# Copy the jar to out/ and extract them.
run cp \
    $SOONG_INT/frameworks/base/tools/hoststubgen/hoststubgen/framework-all-hidden-api-host/linux_glibc_common/gen/* \
    $out

extract $out/*.jar
