#!/bin/bash -
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

make -j44 FrameworksCoreTests
adb install -r -g ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
adb logcat -c

# by default run 5 times
RUN_N=${1:-5}
echo "Running benchmark $RUN_N times"

for (( i=0; i<$RUN_N; i++ ))
do
    adb  shell am instrument -e class 'android.database.NewDatabasePerformanceTestSuite' -w 'com.android.frameworks.coretests/android.support.test.runner.AndroidJUnitRunner'
done

adb logcat -d > /tmp/testlogcat.txt

python frameworks/base/core/tests/coretests/src/android/database/process_newdb_perf_test_logs.py /tmp/testlogcat.txt