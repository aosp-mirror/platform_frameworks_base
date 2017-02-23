#
# Copyright (C) 2016 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)

# Build the legacy-test library
# =============================
# This contains the junit.framework and android.test classes that were in
# Android API level 25.
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_MODULE := legacy-test
LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core-oj core-libart framework

include $(BUILD_JAVA_LIBRARY)

# Build the legacy-android-test library
# =============================
# This contains the android.test classes that were in Android API level 25.
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src/android)
LOCAL_MODULE := legacy-android-test
LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core-oj core-libart framework junit

include $(BUILD_STATIC_JAVA_LIBRARY)

ifeq ($(HOST_OS),linux)
# Build the legacy-performance-test-hostdex library
# =================================================
# This contains the android.test.PerformanceTestCase class only
include $(CLEAR_VARS)

LOCAL_SRC_FILES := src/android/test/PerformanceTestCase.java
LOCAL_MODULE := legacy-performance-test-hostdex

include $(BUILD_HOST_DALVIK_STATIC_JAVA_LIBRARY)
endif  # HOST_OS == linux
