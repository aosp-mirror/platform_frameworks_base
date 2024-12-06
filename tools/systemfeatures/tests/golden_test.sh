#!/usr/bin/env bash

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

set -e

GEN_DIR="tests/gen"
GOLDEN_DIR="tests/golden"

if [[ $(basename $0) == "golden_test.sh" ]]; then
  # We're running via command-line, so we need to:
  #   1) manually update generated srcs
  #   2) use absolute paths
  if [ -z $ANDROID_BUILD_TOP ]; then
    echo "You need to source and lunch before you can use this script directly."
    exit 1
  fi
  GEN_DIR="$ANDROID_BUILD_TOP/out/soong/.intermediates/frameworks/base/tools/systemfeatures/systemfeatures-gen-tests-golden-srcs/gen/$GEN_DIR"
  GOLDEN_DIR="$ANDROID_BUILD_TOP/frameworks/base/tools/systemfeatures/$GOLDEN_DIR"
  rm -rf "$GEN_DIR"
  "$ANDROID_BUILD_TOP"/build/soong/soong_ui.bash --make-mode systemfeatures-gen-tests-golden-srcs
fi

if [[ "$1" == "--update" ]]; then
  rm -rf "$GOLDEN_DIR"
  cp -R "$GEN_DIR" "$GOLDEN_DIR"
  echo "Updated golden test files."
else
  echo "Running diff from test output against golden test files..."
  if diff -ruN "$GOLDEN_DIR" "$GEN_DIR" ; then
    echo "No changes."
  else
    echo
    echo "----------------------------------------------------------------------------------------"
    echo "If changes look OK, run:"
    echo "  \$ANDROID_BUILD_TOP/frameworks/base/tools/systemfeatures/tests/golden_test.sh --update"
    echo "----------------------------------------------------------------------------------------"
    exit 1
  fi
fi
