#!/bin/bash
#
# Copyright (C) 2019 The Android Open Source Project
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
#

function _log()
{
    echo -e "$*" >&2
}

function _eval()
{
    local label="$1"
    local cmd="$2"
    local red="\e[31m"
    local green="\e[32m"
    local reset="\e[0m"
    local output

    _log "${green}[ RUN      ]${reset} ${label}"
    output="$(eval "$cmd" 2>&1)"
    if [[ $? -eq 0 ]]; then
        _log "${green}[       OK ]${reset} ${label}"
        return 0
    else
        echo "${output}"
        _log "${red}[  FAILED  ]${reset} ${label}"
        errors=$((errors + 1))
        return 1
    fi
}

errors=0
script="$(readlink -f "$BASH_SOURCE")"
prefix="$(dirname "$script")"
target_path="${prefix}/tests/data/target/target.apk"
overlay_path="${prefix}/tests/data/overlay/overlay.apk"
idmap_path="/tmp/a.idmap"
valgrind="valgrind --error-exitcode=1 -q --track-origins=yes --leak-check=full"

_eval "idmap2 create" "$valgrind idmap2 create --policy public --target-apk-path $target_path --overlay-apk-path $overlay_path --idmap-path $idmap_path"
_eval "idmap2 dump" "$valgrind idmap2 dump --idmap-path $idmap_path"
_eval "idmap2 lookup" "$valgrind idmap2 lookup --idmap-path $idmap_path --config '' --resid test.target:string/str1"
_eval "idmap2 scan" "$valgrind idmap2 scan --input-directory ${prefix}/tests/data/overlay --recursive --target-package-name test.target --target-apk-path $target_path --output-directory /tmp --override-policy public"
_eval "idmap2 verify" "$valgrind idmap2 verify --idmap-path $idmap_path"
_eval "idmap2_tests" "$valgrind $ANDROID_HOST_OUT/nativetest64/idmap2_tests/idmap2_tests"
exit $errors
