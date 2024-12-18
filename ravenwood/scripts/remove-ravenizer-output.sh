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

# Delete all the ravenizer output jar files from Soong's intermediate directory.

# `-a -prune` is needed because otherwise find would be confused if the directory disappears.

find "${ANDROID_BUILD_TOP:?}/out/soong/.intermediates/" \
    -type d \
    -name 'ravenizer' \
    -print \
    -exec rm -fr \{\} \; \
    -a -prune
