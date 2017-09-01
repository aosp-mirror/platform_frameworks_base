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

LOCAL_PATH:= $(call my-dir)

# =========
# statsd
# =========

include $(CLEAR_VARS)

LOCAL_MODULE := statsd

LOCAL_SRC_FILES := \
    ../../core/java/android/os/IStatsManager.aidl \
    ../../core/java/android/os/IStatsCompanionService.aidl \
    src/StatsService.cpp \
    src/AnomalyMonitor.cpp \
    src/LogEntryPrinter.cpp \
    src/LogReader.cpp \
    src/main.cpp

LOCAL_CFLAGS += \
    -Wall \
    -Werror \
    -Wno-missing-field-initializers \
    -Wno-unused-variable \
    -Wno-unused-function \
    -Wno-unused-parameter

ifeq (debug,)
    LOCAL_CFLAGS += \
            -g -O0
else
    # optimize for size (protobuf glop can get big)
    LOCAL_CFLAGS += \
            -Os
endif

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/../../core/java
LOCAL_C_INCLUDES += $(LOCAL_PATH)/src

LOCAL_SHARED_LIBRARIES := \
        libbase \
        libbinder \
        libcutils \
        libincident \
        liblog \
        libselinux \
        libutils

LOCAL_MODULE_CLASS := EXECUTABLES

#LOCAL_INIT_RC := statsd.rc

include $(BUILD_EXECUTABLE)

# ==============
# statsd_test
# ==============

include $(CLEAR_VARS)

LOCAL_MODULE := statsd_test
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS += \
    -Wall \
    -Werror \
    -Wno-missing-field-initializers \
    -Wno-unused-variable \
    -Wno-unused-function \
    -Wno-unused-parameter

LOCAL_C_INCLUDES += $(LOCAL_PATH)/src

LOCAL_SRC_FILES := \
    ../../core/java/android/os/IStatsManager.aidl \
    src/StatsService.cpp \
    src/LogEntryPrinter.cpp \
    src/LogReader.cpp \
    tests/LogReader_test.cpp \

LOCAL_STATIC_LIBRARIES := \
    libgmock \

LOCAL_SHARED_LIBRARIES := \
    libbase \
    libbinder \
    libcutils \
    liblog \
    libselinux \
    libutils

include $(BUILD_NATIVE_TEST)

