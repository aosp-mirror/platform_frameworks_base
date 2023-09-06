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


# Script to build hoststubgen and run it directly (without using the build rules)
# on framework-all.jar.


echo "THIS SCRIPT IS BROKEN DUE TO CHANGES TO FILE PATHS TO DEPENDENT FILES. FIX IT WHEN YOU NEED TO USE IT." 1>&2

exit 99


source "${0%/*}"/../common.sh

out=out

mkdir -p $out

# Build the tool and target jar.
run m hoststubgen framework-all

base_args=(
  @../hoststubgen/hoststubgen-standard-options.txt

  --in-jar $ANDROID_BUILD_TOP/out/soong/.intermediates/frameworks/base/framework-all/android_common/combined/framework-all.jar
  --policy-override-file ../hoststubgen/framework-policy-override.txt "${@}"

  # This file will contain all classes as an annotation file, with "keep all" policy.
  --gen-keep-all-file $out/framework-all-keep-all-policy.txt

  # This file will contains dump of all classes in the input jar.
  --gen-input-dump-file $out/framework-all-dump.txt
)

do_it() {
  local out_file_stem="$1"
  shift
  local extra_args=("${@}")

  run hoststubgen \
      "${base_args[@]}" \
      "${extra_args[@]}" \
      --out-stub-jar ${out_file_stem}_stub.jar \
      --out-impl-jar ${out_file_stem}_impl.jar \
      $HOSTSTUBGEN_OPTS

  # Extract the jar files, so we can look into them.
  run extract ${out_file_stem}_*.jar
}

#-----------------------------------------------------------------------------
# framework-all, with all hidden APIs.
#-----------------------------------------------------------------------------

# No extra args.
do_it $out/framework-all_host

#-----------------------------------------------------------------------------
# framework-test-api, only public/system/test-APIs in the stub.
#-----------------------------------------------------------------------------

do_it $out/framework-test-api_host \
    --intersect-stub-jar $SOONG_INT/frameworks/base/api/android_test_stubs_current/android_common/combined/*.jar
