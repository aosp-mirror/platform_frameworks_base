#
# Copyright (C) 2013 The Android Open Source Project
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

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    jni_init.cpp \
    com_android_pacprocessor_PacNative.cpp

LOCAL_C_INCLUDES += \
    external/chromium-libpac/src

LOCAL_SHARED_LIBRARIES := \
    libandroidfw \
    libandroid_runtime \
    liblog \
    libutils \
    libnativehelper \
    libpac

LOCAL_MODULE := libjni_pacprocessor
LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_SHARED_LIBRARY)
