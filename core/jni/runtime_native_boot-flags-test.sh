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

# check_zygote_runtime_option CONTEXT RUNTIME_OPTION
# --------------------------------------------------
# Check that all zygote processes are passed RUNTIME_OPTION as runtime option. Use
# CONTEXT in logging.
function check_zygote_runtime_option {
  local context=$1
  local runtime_option=$2

  say \
    "[$context] Check that all zygote processes are passed \`$runtime_option\` as runtime option..."
  for zygote in $zygotes; do
    find_zygote_runtime_option "$zygote" "$runtime_option" \
      || fail "Found no \`$runtime_option\` among runtime options passed to \`$zygote\`"
  done
}

# check_no_zygote_runtime_option CONTEXT RUNTIME_OPTION
# -----------------------------------------------------
# Check that no zygote process is passed RUNTIME_OPTION as runtime option.  Use
# CONTEXT in logging.
function check_no_zygote_runtime_option {
  local context=$1
  local runtime_option=$2

  say "[$context] Check that no zygote process is passed \`$runtime_option\` as runtime option..."
  for zygote in $zygotes; do
    find_zygote_runtime_option "$zygote" "$runtime_option" \
      && fail "Found \`$runtime_option\` among runtime options passed to \`$zygote\`"
  done
}

# check_android_runtime_message CONTEXT MESSAGE
# ---------------------------------------------
# Return whether AndroidRuntime generated MESSAGE in logcat. Use CONTEXT in
# logging.
function check_android_runtime_message {
  local context=$1
  local message=$2

  say "[$context] Check that AndroidRuntime generated expected message in logcat..."
  adb logcat -d -s AndroidRuntime | grep -F -q "$message" \
    || fail "Found no message \"$message\" generated by AndroidRuntime"
}

# check_no_android_runtime_message CONTEXT MESSAGE
# ------------------------------------------------
# Return whether AndroidRuntime did not generate MESSAGE in logcat. Use CONTEXT
# in logging.
function check_no_android_runtime_message {
  local context=$1
  local message=$2

  say "[$context] Check that AndroidRuntime did not generate unexpected message in logcat..."
  adb logcat -d -s AndroidRuntime | grep -F -q -v "$message" \
    || fail "Found message \"$message\" generated by AndroidRuntime"
}

# test_android_runtime_flag FLAG VALUE CHECK_EFFECT CHECK_NO_EFFECT
# -----------------------------------------------------------------
# Test device configuration FLAG with VALUE. CHECK_EFFECT and CHECK_NO_EFFECT
# are functions that are passed a context as sole argument and that respectively
# check the effect or the absence of effect of the flag.
function test_android_runtime_flag {
  local flag=$1
  local value=$2
  local check_effect=$3
  local check_no_effect=$4

  # Persistent system property (set after a reboot) associated with the device
  # configuration flag.
  local prop="persist.device_config.$namespace.$flag"

  banner "Testing \`$flag\` value \`$value\`."

  say "Setting device configuration flag..."
  adb shell device_config put "$namespace" "$flag" "$value"
  # Give some time to the device to digest this change before rebooting.
  sleep 3

  # Check that both the device configuration flag and the associated system
  # property are set, but that flag has not produced an effect on the system (as
  # we haven't rebooted yet).
  local context="Flag set, before reboot"
  check_device_config_flag "$context" "$flag" "$value"
  check_system_property "$context" "$prop" "$value"
  $check_no_effect "$context"

  # Reboot device for the flag value to take effect.
  reboot_and_wait_for_device
  context="Flag set, after 1st reboot"
  check_device_config_flag "$context" "$flag" "$value"
  check_system_property "$context" "$prop" "$value"
  $check_effect "$context"

  # Reboot device a second time and check that the state has persisted.
  reboot_and_wait_for_device
  context="Flag set, after 2nd reboot"
  check_device_config_flag "$context" "$flag" "$value"
  check_system_property "$context" "$prop" "$value"
  $check_effect "$context"

  say "Unsetting device configuration flag..."
  adb shell device_config delete "$namespace" "$flag" >/dev/null
  # Give some time to the device to digest this change before rebooting.
  sleep 3

  # Reboot and check that the device is back to its default state.
  reboot_and_wait_for_device
  context="Flag unset, after 3rd reboot"
  check_no_device_config_flag "$context" "$flag"
  check_no_system_property "$context" "$prop"
  $check_no_effect "$context"
}


# Pre-test actions.
# =================

# Enumerate Zygote processes.
case $(adb shell getprop ro.zygote) in
  (zygote32) zygotes="zygote";;
  (zygote64) zygotes="zygote64";;
  (zygote32_64|zygote64_32) zygotes="zygote zygote64";;
esac

# Test "enable_generational_cc" flag values.
# ==========================================

function check_nogenerational_cc {
  check_zygote_runtime_option "$1" "-Xgc:nogenerational_cc"
}
function check_no_nogenerational_cc {
  check_no_zygote_runtime_option "$1" "-Xgc:nogenerational_cc"
}

function check_generational_cc {
  check_zygote_runtime_option "$1" "-Xgc:generational_cc"
}
function check_no_generational_cc {
  check_no_zygote_runtime_option "$1" "-Xgc:generational_cc"
}

test_android_runtime_flag \
  enable_generational_cc false check_nogenerational_cc check_no_nogenerational_cc
test_android_runtime_flag \
  enable_generational_cc true check_generational_cc check_no_generational_cc

# Test "enable_apex_image" flag values.
# =====================================

default_boot_image_message="Using default boot image"
function check_default_boot_image {
  check_android_runtime_message "$1" "$default_boot_image_message"
}
function check_no_default_boot_image {
  check_no_android_runtime_message "$1" "$default_boot_image_message"
}

apex_boot_image_option="-Ximage:/system/framework/apex.art"
apex_boot_image_message="Using Apex boot image: '$apex_boot_image_option'"
function check_apex_boot_image {
  check_zygote_runtime_option "$1" "$apex_boot_image_option"
  check_android_runtime_message "$1" "$apex_boot_image_message"
}
function check_no_apex_boot_image {
  check_no_zygote_runtime_option "$1" "$apex_boot_image_option"
  check_no_android_runtime_message "$1" "$apex_boot_image_message"
}

test_android_runtime_flag \
  enable_apex_image false check_default_boot_image check_no_default_boot_image
test_android_runtime_flag \
  enable_apex_image true check_apex_boot_image check_no_apex_boot_image

# Post-test actions.
# ==================

if [[ "$exit_status" -eq 0 ]]; then
  banner "All tests passed."
else
  banner "Test(s) failed."
fi
exit $exit_status
