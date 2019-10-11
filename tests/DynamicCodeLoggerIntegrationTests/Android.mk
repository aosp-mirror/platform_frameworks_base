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
LOCAL_MODULE := DynamicCodeLoggerTestLibrary
LOCAL_SRC_FILES := $(call all-java-files-under, src/com/android/dcl)

include $(BUILD_JAVA_LIBRARY)

dynamiccodeloggertest_jar := $(LOCAL_BUILT_MODULE)


# Also build a native library that the test app can dynamically load

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_MODULE := DynamicCodeLoggerNativeTestLibrary
LOCAL_SRC_FILES := src/cpp/com_android_dcl_Jni.cpp
LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE)
LOCAL_SDK_VERSION := 28
LOCAL_NDK_STL_VARIANT := c++_static

include $(BUILD_SHARED_LIBRARY)

# And a standalone native executable that we can exec.

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_MODULE := DynamicCodeLoggerNativeExecutable
LOCAL_SRC_FILES := src/cpp/test_executable.cpp

include $(BUILD_EXECUTABLE)

dynamiccodeloggertest_executable := $(LOCAL_BUILT_MODULE)

# Build the test app itself

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := DynamicCodeLoggerIntegrationTests
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_CERTIFICATE := shared
LOCAL_SRC_FILES := $(call all-java-files-under, src/com/android/server/pm)

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.test.rules \
    truth-prebuilt \

# Include both versions of the .so if we have 2 arch
LOCAL_MULTILIB := both
LOCAL_JNI_SHARED_LIBRARIES := \
    DynamicCodeLoggerNativeTestLibrary \

# This gets us the javalib.jar built by DynamicCodeLoggerTestLibrary above as well as the various
# native binaries.
LOCAL_JAVA_RESOURCE_FILES := \
    $(dynamiccodeloggertest_jar) \
    $(dynamiccodeloggertest_executable) \

include $(BUILD_PACKAGE)
