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

LOCAL_MODULE := incidentd

LOCAL_SRC_FILES := \
        src/FdBuffer.cpp \
        src/IncidentService.cpp \
        src/Reporter.cpp \
        src/Section.cpp \
        src/main.cpp \
        src/protobuf.cpp \
        src/report_directory.cpp \
        src/section_list.cpp

LOCAL_CFLAGS += \
        -Wall -Werror -Wno-missing-field-initializers -Wno-unused-variable -Wunused-parameter

ifeq (debug,)
    LOCAL_CFLAGS += \
            -g -O0
else
    # optimize for size (protobuf glop can get big)
    LOCAL_CFLAGS += \
            -Os
endif

LOCAL_SHARED_LIBRARIES := \
        libbase \
        libbinder \
        libcutils \
        libincident \
        liblog \
        libselinux \
        libservices \
        libutils

ifeq (BUILD_WITH_INCIDENTD_RC,true)
LOCAL_INIT_RC := incidentd.rc
endif

include $(BUILD_EXECUTABLE)
