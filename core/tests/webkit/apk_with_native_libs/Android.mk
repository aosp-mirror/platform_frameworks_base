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

LOCAL_PATH := $(call my-dir)
MY_PATH := $(LOCAL_PATH)

# Set shared variables
MY_MODULE_TAGS := optional
MY_JNI_SHARED_LIBRARIES := libwebviewtest_jni
MY_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
MY_SRC_FILES := $(call all-java-files-under, src)
MY_CFLAGS := -Wall -Werror
MY_SDK_VERSION := system_current
MY_PROGUARD_ENABLED := disabled
MY_MULTILIB := both

# Recurse down the file tree.
include $(call all-subdir-makefiles)



# Builds an apk containing native libraries that will be unzipped on the device.
include $(CLEAR_VARS)

LOCAL_PATH := $(MY_PATH)
LOCAL_PACKAGE_NAME := WebViewLoadingOnDiskTestApk
LOCAL_MANIFEST_FILE := ondisk/AndroidManifest.xml

LOCAL_MODULE_TAGS := $(MY_MODULE_TAGS)
LOCAL_JNI_SHARED_LIBRARIES := $(MY_JNI_SHARED_LIBRARIES)
LOCAL_MODULE_PATH := $(MY_MODULE_PATH)
LOCAL_SRC_FILES := $(MY_SRC_FILES)
LOCAL_CFLAGS := $(MY_CFLAGS)
LOCAL_SDK_VERSION := $(MY_SDK_VERSION)
LOCAL_PROGUARD_ENABLED := $(MY_PROGUARD_ENABLED)
LOCAL_MULTILIB := $(MY_MULTILIB)
LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)


# Builds an apk containing uncompressed native libraries that have to be
# accessed through the APK itself on the device.
include $(CLEAR_VARS)

LOCAL_PATH := $(MY_PATH)
LOCAL_PACKAGE_NAME := WebViewLoadingFromApkTestApk
LOCAL_MANIFEST_FILE := inapk/AndroidManifest.xml

LOCAL_MODULE_TAGS := $(MY_MODULE_TAGS)
LOCAL_JNI_SHARED_LIBRARIES := $(MY_JNI_SHARED_LIBRARIES)
LOCAL_MODULE_PATH := $(MY_MODULE_PATH)
LOCAL_SRC_FILES := $(MY_SRC_FILES)
LOCAL_CFLAGS := $(MY_CFLAGS)
LOCAL_SDK_VERSION := $(MY_SDK_VERSION)
LOCAL_PROGUARD_ENABLED := $(MY_PROGUARD_ENABLED)
LOCAL_MULTILIB := $(MY_MULTILIB)
LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
