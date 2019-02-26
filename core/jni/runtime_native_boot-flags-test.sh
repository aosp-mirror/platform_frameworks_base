#!/bin/bash

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

# Test Android Runtime (Boot) device configuration flags (living in namespace
# `runtime_native_boot`).

me=$(basename $0)

# Namespace containing the tested flag.
namespace=runtime_native_boot
# Default set of checked zygote processes.
zygotes=

# Status of whole test script.
exit_status=0

function say {
  echo "$me: $*"
}

function banner {
  local separator=$(echo "$@" | sed s/./=/g )
  say "$separator"
  say "$@"
  say "$separator"
}

function fail {
  say "FAILED: $@"
  exit_status=1
}

function reboot_and_wait_for_device {
  say "Rebooting device..."
  adb reboot
  adb wait-for-device >/dev/null
  # Wait until the device has finished booting. Give the device 60 iterations
  # (~60 seconds) to try and finish booting before declaring defeat.
  local niters=60
  for i in $(seq $niters); do
    [[ $(adb shell getprop sys.boot_completed) -eq 1 ]] && return 0
    sleep 1
  done
  fail "Device did not finish booting before timeout (~$niters seconds)"
}

# check_device_config_flag CONTEXT FLAG VALUE
# -------------------------------------------
# Check that the device configuration flag FLAG is set to VALUE. Use CONTEXT in
# logging.
function check_device_config_flag {
  local context=$1
  local flag=$2
  local value=$3

  say "[$context] Check that the device configuration flag is set..."
  local flag_value=$(adb shell device_config get "$namespace" "$flag")
  [[ "$flag_value" = "$value" ]] \
    || fail "Device configuration flag \`$flag\` set to \`$flag_value\` (expected \`$value\`)"
}

# check_no_device_config_flag CONTEXT FLAG
# ----------------------------------------
# Check that the device configuration flag FLAG is not set. Use CONTEXT in
# logging.
function check_no_device_config_flag {
  local context=$1
  local flag=$2

  say "[$context] Check that the device configuration flag is not set..."
  local flag_value=$(adb shell device_config get "$namespace" "$flag")
  [[ "$flag_value" = null ]] \
    || fail "Device configuration flag \`$flag\` set to \`$flag_value\` (expected `null`)"
}

# get_system_property PROP
# ------------------------
# Get system property PROP associated with a device configuration flag.
function get_system_property {
  local prop=$1

  # Note that we need to be root to read that system property.
  adb root >/dev/null
  local prop_value=$(adb shell getprop "$prop")
  adb unroot >/dev/null
  echo "$prop_value"
}

# check_system_property CONTEXT PROP VALUE
# ----------------------------------------
# Check that the system property PROP associated with a device configuration
# flag is set to VALUE. Use CONTEXT in logging.
function check_system_property {
  local context=$1
  local prop=$2
  local value=$3

  say "[$context] Check that the persistent system property is set..."
  local prop_value=$(get_system_property "$prop")
  [[ "$prop_value" = "$value" ]] \
    || fail "System property \`$prop\` set to \`$prop_value\` (expected \`$value\`)"
}

# check_no_system_property CONTEXT PROP
# -------------------------------------
# Check that the system property PROP associated with a device configuration
# flag is not set. Use CONTEXT in logging.
function check_no_system_property {
  local context=$1
  local prop=$2

  say "[$context] Check that the persistent system property is not set..."
  local prop_value=$(get_system_property "$prop")
  [[ -z "$prop_value" ]] \
    || fail "System property \`$prop\` set to \`$prop_value\` (expected unset property)"
}

# find_zygote_runtime_option ZYGOTE RUNTIME_OPTION
# ------------------------------------------------
# Return whether ZYGOTE is passed RUNTIME_OPTION.
function find_zygote_runtime_option {
  local zygote=$1
  local runtime_option=$2

  adb logcat -d -s "$zygote" | grep -q -e "option\[[0-9]\+\]=$runtime_option"
}

# check_zygote_gc_runtime_option CONTEXT VALUE
# --------------------------------------------
# Check that all zygote processes are passed device configuration flag VALUE as
# GC runtime option. Use CONTEXT in logging.
function check_zygote_gc_runtime_option {
  local context=$1
  local value=$2

  say \
    "[$context] Check that all zygote processes are passed the flag value as a GC runtime option..."
  local runtime_option="-Xgc:$value"
  for zygote in $zygotes; do
    find_zygote_runtime_option "$zygote" "$runtime_option"\
      || fail "Found no \`$runtime_option\` among runtime options passed to \`$zygote\`"
  done
}

# check_no_zygote_gc_runtime_option CONTEXT VALUE
# -----------------------------------------------
# Check that no zygote process is passed device configuration flag VALUE as GC
# runtime option.  Use CONTEXT in logging.
function check_no_zygote_gc_runtime_option {
  local context=$1
  local value=$2

  say "[$context] Check no zygote process is passed the flag value as a GC runtime option..."
  local runtime_option="-Xgc:$value"
  for zygote in $zygotes; do
    find_zygote_runtime_option "$zygote" "$runtime_option"\
      && fail "Found \`$runtime_option\` among runtime options passed to \`$zygote\`"
  done
}

# test_android_runtime_flag FLAG VALUE
# ------------------------------------
# Test device configuration FLAG with VALUE.
function test_android_runtime_flag {
  local flag=$1
  local value=$2

  # Persistent system property (set after a reboot) associated with the device
  # configuration flag.
  local prop="persist.device_config.$namespace.$flag"

  banner "Testing \`$flag\` value \`$value\`."

  say "Setting device configuration flag..."
  adb shell device_config put "$namespace" "$flag" "$value"
  # Give some time to the device to digest this change before rebooting.
  sleep 3

  # Check that both the device configuration flag and the associated system
  # property are set, but that the zygote hasn't had the flag passed to it as a
  # GC runtime option (as we haven't rebooted yet).
  local context="Flag set, before reboot"
  check_device_config_flag "$context" "$flag" "$value"
  check_system_property "$context" "$prop" "$value"
  check_no_zygote_gc_runtime_option "$context" "$value"

  # Reboot device for the flag value to take effect.
  reboot_and_wait_for_device
  context="Flag set, after 1st reboot"
  check_device_config_flag "$context" "$flag" "$value"
  check_system_property "$context" "$prop" "$value"
  check_zygote_gc_runtime_option "$context" "$value"

  # Reboot device a second time and check that the state has persisted.
  reboot_and_wait_for_device
  context="Flag set, after 2nd reboot"
  check_device_config_flag "$context" "$flag" "$value"
  check_system_property "$context" "$prop" "$value"
  check_zygote_gc_runtime_option "$context" "$value"

  say "Unsetting device configuration flag..."
  adb shell device_config delete "$namespace" "$flag" >/dev/null
  # Give some time to the device to digest this change before rebooting.
  sleep 3

  # Reboot and check that the device is back to its default state.
  reboot_and_wait_for_device
  context="Flag unset, after 3rd reboot"
  check_no_device_config_flag "$context" "$flag"
  check_no_system_property "$context" "$prop"
  check_no_zygote_gc_runtime_option "$context" "$value"
}

# Enumerate Zygote processes.
case $(adb shell getprop ro.zygote) in
  (zygote32) zygotes="zygote";;
  (zygote64) zygotes="zygote64";;
  (zygote32_64|zygote64_32) zygotes="zygote zygote64";;
esac

# Test "gctype" flag values.
test_android_runtime_flag gctype nogenerational_cc
test_android_runtime_flag gctype generational_cc

if [[ "$exit_status" -eq 0 ]]; then
  banner "All tests passed."
else
  banner "Test(s) failed."
fi
exit $exit_status
