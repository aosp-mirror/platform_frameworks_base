#!/bin/bash

# Copyright (C) 2018 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the 'License');
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an 'AS IS' BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

if git show --name-only --pretty=format: $1 | grep api/ > /dev/null; then
  python tools/apilint/apilint.py \
    --base-current <(git show $1:api/current.txt) \
    --base-previous <(git show $1^:api/current.txt) \
    <(git show $1:api/system-current.txt) \
    <(git show $1^:api/system-current.txt)
fi
