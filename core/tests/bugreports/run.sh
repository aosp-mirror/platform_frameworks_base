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

# Script to run bugreport unitests
# Must run on a rooted device.
# Must run lunch before running the script
# Usage: ${ANDROID_BUILD_TOP}/frameworks/base/core/tests/bugreports/run.sh

# NOTE: This script replaces the framework-sysconfig.xml on your device, so use with caution.
# It tries to replace it when done, but if the script does not finish cleanly
# (for e.g. force stopped mid-way) your device will be left in an inconsistent state.
# Reflashing will restore the right config.

TMP_SYS_CONFIG=/var/tmp/framework-sysconfig.xml

if [[ -z $ANDROID_PRODUCT_OUT ]]; then
  echo "Please lunch before running this test."
  exit 0
fi

# Print every command to console.
set -x

make -j BugreportManagerTestCases &&
    adb root &&
    adb remount &&
    adb wait-for-device &&
    # Save the sysconfig file in a tmp location and push the test config in
    adb pull /system/etc/sysconfig/framework-sysconfig.xml "${TMP_SYS_CONFIG}" &&
    adb push $ANDROID_BUILD_TOP/frameworks/base/core/tests/bugreports/config/test-sysconfig.xml /system/etc/sysconfig/framework-sysconfig.xml &&
    # The test app needs to be a priv-app.
    adb push $OUT/testcases/BugreportManagerTestCases/*/BugreportManagerTestCases.apk /system/priv-app ||
    exit 1

adb reboot &&
adb wait-for-device &&
atest BugreportManagerTest || echo "Tests FAILED!"

# Restore the saved config file
if [ -f "${TMP_SYS_CONFIG}" ]; then
    SIZE=$(stat --printf="%s" "${TMP_SYS_CONFIG}")
    if [ SIZE > 0 ]; then
        adb remount &&
        adb wait-for-device &&
        adb push "${TMP_SYS_CONFIG}" /system/etc/sysconfig/framework-sysconfig.xml &&
        rm "${TMP_SYS_CONFIG}"
    fi
fi
