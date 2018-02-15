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

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := libincident

LOCAL_CFLAGS := \
        -Wall -Werror -Wno-missing-field-initializers -Wno-unused-variable -Wunused-parameter

LOCAL_SHARED_LIBRARIES := \
        libbinder \
        liblog \
        libutils

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/../../core/java
LOCAL_C_INCLUDES := \
        $(LOCAL_PATH)/include

LOCAL_SRC_FILES := \
        ../../core/java/android/os/IIncidentManager.aidl \
        ../../core/java/android/os/IIncidentReportStatusListener.aidl \
        proto/android/os/header.proto \
        proto/android/os/metadata.proto \
        src/IncidentReportArgs.cpp

LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)/include
LOCAL_PROTO_OPTIMIZE_TYPE := lite

include $(BUILD_SHARED_LIBRARY)

