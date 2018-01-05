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


############################################################
# FrameworksServicesLib app just for Robolectric test target.  #
############################################################
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := FrameworksServicesLib
LOCAL_MODULE_TAGS := optional

LOCAL_PRIVILEGED_MODULE := true

LOCAL_STATIC_JAVA_LIBRARIES := \
    services.backup \
    services.core

include $(BUILD_PACKAGE)

#############################################
# FrameworksServices Robolectric test target. #
#############################################
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

# Include the testing libraries (JUnit4 + Robolectric libs).
LOCAL_STATIC_JAVA_LIBRARIES := \
    platform-robolectric-android-all-stubs \
    android-support-test \
    mockito-robolectric-prebuilt \
    platform-test-annotations \
    truth-prebuilt \
    testng

LOCAL_JAVA_LIBRARIES := \
    junit \
    platform-robolectric-3.6.1-prebuilt

LOCAL_INSTRUMENTATION_FOR := FrameworksServicesLib
LOCAL_MODULE := FrameworksServicesRoboTests

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_JAVA_LIBRARY)

#############################################################
# FrameworksServices runner target to run the previous target. #
#############################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunFrameworksServicesRoboTests

LOCAL_SDK_VERSION := current

LOCAL_STATIC_JAVA_LIBRARIES := \
    FrameworksServicesRoboTests

LOCAL_TEST_PACKAGE := FrameworksServicesLib

LOCAL_INSTRUMENT_SOURCE_DIRS := $(dir $(LOCAL_PATH))backup/java

include prebuilts/misc/common/robolectric/3.6.1/run_robotests.mk
