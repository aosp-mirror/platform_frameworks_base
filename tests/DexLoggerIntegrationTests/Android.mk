#
# Copyright 2017 The Android Open Source Project
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

# Build a tiny library that the test app can dynamically load

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_MODULE := DexLoggerTestLibrary
LOCAL_SRC_FILES := $(call all-java-files-under, src/com/android/dcl)

include $(BUILD_JAVA_LIBRARY)

dexloggertest_jar := $(LOCAL_BUILT_MODULE)


# Build the test app itself

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := DexLoggerIntegrationTests
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_CERTIFICATE := platform
LOCAL_SRC_FILES := $(call all-java-files-under, src/com/android/server/pm)

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-test \
    truth-prebuilt \

# This gets us the javalib.jar built by DexLoggerTestLibrary above.
LOCAL_JAVA_RESOURCE_FILES := $(dexloggertest_jar)

include $(BUILD_PACKAGE)
