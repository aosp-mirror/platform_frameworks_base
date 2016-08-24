# Copyright (C) 2012 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    com_android_frameworks_coretests_JNITest.cpp

LOCAL_SHARED_LIBRARIES := \
    libnativehelper

LOCAL_CFLAGS += -Wall -Werror

LOCAL_MODULE := libframeworks_coretests_jni

# this does not prevent build system
# from installing library to /system/lib
LOCAL_MODULE_TAGS := tests

# .. we want to avoid that... so we put it somewhere
# bionic linker cant find it without outside help (nativetests):
LOCAL_MODULE_PATH_32 := $($(TARGET_2ND_ARCH_VAR_PREFIX)TARGET_OUT_DATA_NATIVE_TESTS)/$(LOCAL_MODULE)
LOCAL_MODULE_PATH_64 := $(TARGET_OUT_DATA_NATIVE_TESTS)/$(LOCAL_MODULE)

include $(BUILD_SHARED_LIBRARY)
