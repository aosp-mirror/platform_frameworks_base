# Copyright (C) 2010 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)

# Provides C++ wrappers for system services.

include $(CLEAR_VARS)

LOCAL_MODULE := libservices
LOCAL_SRC_FILES := \
    ../../core/java/com/android/internal/os/IDropBoxManagerService.aidl \
    src/os/DropBoxManager.cpp

LOCAL_AIDL_INCLUDES := \
    $(LOCAL_PATH)/../../core/java
LOCAL_C_INCLUDES := \
    system/core/include
LOCAL_SHARED_LIBRARIES := \
    libbinder \
    liblog \
    libcutils \
    libutils

LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)/include
LOCAL_C_INCLUDES += $(LOCAL_PATH)/include

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_SHARED_LIBRARY)


