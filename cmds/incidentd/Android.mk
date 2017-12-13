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

# ========= #
# incidentd #
# ========= #

include $(CLEAR_VARS)

LOCAL_MODULE := incidentd

LOCAL_SRC_FILES := \
        src/PrivacyBuffer.cpp \
        src/FdBuffer.cpp \
        src/IncidentService.cpp \
        src/Privacy.cpp \
        src/Reporter.cpp \
        src/Section.cpp \
        src/io_util.cpp \
        src/main.cpp \
        src/report_directory.cpp

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

LOCAL_C_INCLUDES += $(LOCAL_PATH)/src

LOCAL_SHARED_LIBRARIES := \
        libbase \
        libbinder \
        libcutils \
        libincident \
        liblog \
        libprotoutil \
        libselinux \
        libservices \
        libutils

LOCAL_MODULE_CLASS := EXECUTABLES
gen_src_dir := $(local-generated-sources-dir)

GEN := $(gen_src_dir)/src/section_list.cpp
$(GEN): $(HOST_OUT_EXECUTABLES)/incident-section-gen
$(GEN): PRIVATE_CUSTOM_TOOL = \
    $(HOST_OUT_EXECUTABLES)/incident-section-gen incidentd > $@
$(GEN): $(HOST_OUT_EXECUTABLES)/incident-section-gen
	$(transform-generated-source)
LOCAL_GENERATED_SOURCES += $(GEN)

gen_src_dir:=
GEN:=

ifeq ($(BUILD_WITH_INCIDENTD_RC), true)
LOCAL_INIT_RC := incidentd.rc
endif

include $(BUILD_EXECUTABLE)

# ============== #
# incidentd_test #
# ============== #

include $(CLEAR_VARS)

LOCAL_MODULE := incidentd_test
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS := -Werror -Wall -Wno-unused-variable -Wunused-parameter

LOCAL_C_INCLUDES += $(LOCAL_PATH)/src

LOCAL_SRC_FILES := \
    src/PrivacyBuffer.cpp \
    src/FdBuffer.cpp \
    src/Privacy.cpp \
    src/Reporter.cpp \
    src/Section.cpp \
    src/io_util.cpp \
    src/report_directory.cpp \
    tests/section_list.cpp \
    tests/PrivacyBuffer_test.cpp \
    tests/FdBuffer_test.cpp \
    tests/Reporter_test.cpp \
    tests/Section_test.cpp \

LOCAL_STATIC_LIBRARIES := \
    libgmock \

LOCAL_SHARED_LIBRARIES := \
    libbase \
    libbinder \
    libcutils \
    libincident \
    liblog \
    libprotoutil \
    libselinux \
    libservices \
    libutils \

LOCAL_TEST_DATA := $(call find-test-data-in-subdirs, $(LOCAL_PATH), *, testdata)

include $(BUILD_NATIVE_TEST)
