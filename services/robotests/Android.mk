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

###################################################################
# FrameworksServicesLib app just for Robolectric test target      #
###################################################################
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PACKAGE_NAME := FrameworksServicesLib
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_MODULE_TAGS := optional

LOCAL_PRIVILEGED_MODULE := true

LOCAL_STATIC_JAVA_LIBRARIES := \
    services.core \
    services.net

include $(BUILD_PACKAGE)

###################################################################
# FrameworksServicesLib Robolectric test target.                  #
###################################################################
include $(CLEAR_VARS)

LOCAL_MODULE := FrameworksServicesRoboTests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/res

LOCAL_JAVA_RESOURCE_DIRS := config

# Include the testing libraries
LOCAL_JAVA_LIBRARIES := \
    platform-test-annotations \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth-prebuilt \
    testng

LOCAL_INSTRUMENTATION_FOR := FrameworksServicesLib

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_JAVA_LIBRARY)

###################################################################
# FrameworksServicesLib runner target to run the previous target. #
###################################################################
include $(CLEAR_VARS)

LOCAL_MODULE := RunFrameworksServicesRoboTests

LOCAL_JAVA_LIBRARIES := \
    FrameworksServicesRoboTests \
    platform-test-annotations \
    robolectric_android-all-stub \
    Robolectric_all-target \
    mockito-robolectric-prebuilt \
    truth-prebuilt \
    testng

LOCAL_TEST_PACKAGE := FrameworksServicesLib

LOCAL_ROBOTEST_FILES := $(call find-files-in-subdirs,$(LOCAL_PATH)/src,*Test.java,.)

include external/robolectric-shadows/run_robotests.mk

###################################################################
# include subdir Android.mk files
###################################################################
include $(CLEAR_VARS)
include $(call all-makefiles-under,$(LOCAL_PATH))
