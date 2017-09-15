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

# ================
# proto static lib
# ================
include $(CLEAR_VARS)

LOCAL_MODULE := statsd_proto
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-proto-files-under, src)

LOCAL_PROTOC_FLAGS :=
LOCAL_PROTOC_OPTIMIZE_TYPE := lite-static

include $(BUILD_STATIC_LIBRARY)

STATSD_PROTO_INCLUDES := $(local-generated-sources-dir)/src/$(LOCAL_PATH)

# =========
# statsd
# =========

include $(CLEAR_VARS)

LOCAL_MODULE := statsd

LOCAL_SRC_FILES := \
    ../../core/java/android/os/IStatsManager.aidl \
    src/StatsService.cpp \
    src/LogEntryPrinter.cpp \
    src/LogReader.cpp \
    src/main.cpp \
    src/DropboxWriter.cpp \
    src/parse_util.cpp \
    src/StatsLogProcessor.cpp \
    src/stats_log.proto \
    src/statsd_config.proto \
    src/stats_constants.proto \
    src/DropboxReader.cpp \


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
LOCAL_C_INCLUDES += $(LOCAL_PATH)/src \
	STATSD_PROTO_INCLUDES

LOCAL_STATIC_LIBRARIES := statsd_proto

LOCAL_SHARED_LIBRARIES := \
        libbase \
        libbinder \
        libcutils \
        libincident \
        liblog \
        libselinux \
        libutils \
        libservices \
        libandroidfw \
        libprotobuf-cpp-lite \

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

LOCAL_C_INCLUDES += $(LOCAL_PATH)/src \
	STATSD_PROTO_INCLUDES

LOCAL_SRC_FILES := \
    ../../core/java/android/os/IStatsManager.aidl \
    src/StatsService.cpp \
    src/LogEntryPrinter.cpp \
    src/LogReader.cpp \
    tests/LogReader_test.cpp \

LOCAL_STATIC_LIBRARIES := \
    libgmock \
    statsd_proto

LOCAL_SHARED_LIBRARIES := \
    libbase \
    libbinder \
    libcutils \
    liblog \
    libselinux \
    libutils \
    libprotobuf-cpp-lite \

include $(BUILD_NATIVE_TEST)
