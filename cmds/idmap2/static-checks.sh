#!/bin/bash
#
# Copyright (C) 2018 The Android Open Source Project
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

    _log "${green}[ RUN      ]${reset} ${label}"
    local output="$(eval "$cmd")"
    if [[ -z "${output}" ]]; then
        _log "${green}[       OK ]${reset} ${label}"
        return 0
    else
        echo "${output}"
        _log "${red}[  FAILED  ]${reset} ${label}"
        errors=$((errors + 1))
        return 1
    fi
}

function _clang_format()
{
    local path
    local errors=0

    for path in $cpp_files; do
        local output="$(clang-format -style=file "$path" | diff $path -)"
        if [[ "$output" ]]; then
            echo "$path"
            echo "$output"
            errors=1
        fi
    done
    return $errors
}

function _bpfmt()
{
    local output="$(bpfmt -s -d $bp_files)"
    if [[ "$output" ]]; then
        echo "$output"
        return 1
    fi
    return 0
}

function _cpplint()
{
    local cpplint="${ANDROID_BUILD_TOP}/tools/repohooks/tools/cpplint.py"
    local output="$($cpplint --quiet $cpp_files 2>&1 >/dev/null | grep -v \
        -e 'Found C system header after C++ system header.' \
        -e 'Unknown NOLINT error category: misc-non-private-member-variables-in-classes' \
    )"
    if [[ "$output" ]]; then
        echo "$output"
        return 1
    fi
    return 0
}

function _parse_args()
{
    local opts

    opts="$(getopt -o cfh --long check,fix,help -- "$@")"
    if [[ $? -ne 0 ]]; then
        exit 1
    fi
    eval set -- "$opts"
    while true; do
        case "$1" in
            -c|--check) opt_mode="check"; shift ;;
            -f|--fix) opt_mode="fix"; shift ;;
            -h|--help) opt_mode="help"; shift ;;
            *) break ;;
        esac
    done
}

errors=0
script="$(readlink -f "$BASH_SOURCE")"
prefix="$(dirname "$script")"
cpp_files="$(find "$prefix" -name '*.cpp' -or -name '*.h')"
bp_files="$(find "$prefix" -name 'Android.bp')"
opt_mode="check"

_parse_args "$@"
if [[ $opt_mode == "check" ]]; then
    _eval "clang-format" "_clang_format"
    _eval "bpfmt" "_bpfmt"
    _eval "cpplint" "_cpplint"
    exit $errors
elif [[ $opt_mode == "fix" ]]; then
    clang-format -style=file -i $cpp_files
    bpfmt -s -w $bp_files
    exit 0
elif [[ $opt_mode == "help" ]]; then
    echo "Run static analysis tools such as clang-format and cpplint on the idmap2"
    echo "module. Optionally fix some of the issues found (--fix). Intended to be run"
    echo "before merging any changes."
    echo
    echo "usage: $(basename $script) [--check|--fix|--help]"
    exit 0
else
    exit 1
fi
