#!/bin/bash
#
# Copyright (C) 2017 The Android Open Source Project
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
if [ "$#" -lt 2 ]; then
  echo "Usage $0 <input classes file> <blacklist file> [extra classes files]"
  exit 1
fi

# Write file headers first
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
cat "$DIR/copyright-header"
echo "# Preloaded-classes filter file for phones.
#
# Classes in this file will be allocated into the boot image, and forcibly initialized in
# the zygote during initialization. This is a trade-off, using virtual address space to share
# common heap between apps.
#
# This file has been derived for mainline phone (and tablet) usage.
#"

input=$1
blacklist=$2
shift 2
extra_classes_files=("$@")

# Disable locale to enable lexicographical sorting
LC_ALL=C sort "$input" "${extra_classes_files[@]}" | uniq | grep -f "$blacklist" -v -F -x
