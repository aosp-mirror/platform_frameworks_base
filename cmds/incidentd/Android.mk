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

# proto files used in incidentd to generate cppstream proto headers.
PROTO_FILES:= \
        frameworks/base/core/proto/android/os/backtrace.proto \
        frameworks/base/core/proto/android/os/data.proto \
        frameworks/base/core/proto/android/util/log.proto

# ========= #
# incidentd #
# ========= #

include $(CLEAR_VARS)

LOCAL_MODULE := incidentd

LOCAL_SRC_FILES := $(call all-cpp-files-under, src) \

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
        libdebuggerd_client \
        libdumputils \
        libincident \
        liblog \
        libprotoutil \
        libservices \
        libutils

LOCAL_MODULE_CLASS := EXECUTABLES

gen_src_dir := $(local-generated-sources-dir)

# generate section_list.cpp
GEN_LIST := $(gen_src_dir)/src/section_list.cpp
$(GEN_LIST): $(HOST_OUT_EXECUTABLES)/incident-section-gen
$(GEN_LIST): PRIVATE_CUSTOM_TOOL = \
    $(HOST_OUT_EXECUTABLES)/incident-section-gen incidentd > $@
$(GEN_LIST): $(HOST_OUT_EXECUTABLES)/incident-section-gen
	$(transform-generated-source)
LOCAL_GENERATED_SOURCES += $(GEN_LIST)
GEN_LIST:=

# generate cppstream proto, add proto files to PROTO_FILES
GEN_PROTO := $(gen_src_dir)/proto.timestamp
$(GEN_PROTO): $(HOST_OUT_EXECUTABLES)/aprotoc $(HOST_OUT_EXECUTABLES)/protoc-gen-cppstream $(PROTO_FILES)
$(GEN_PROTO): PRIVATE_GEN_SRC_DIR := $(gen_src_dir)
$(GEN_PROTO): PRIVATE_CUSTOM_TOOL = \
    $(HOST_OUT_EXECUTABLES)/aprotoc --plugin=protoc-gen-cppstream=$(HOST_OUT_EXECUTABLES)/protoc-gen-cppstream \
        --cppstream_out=$(PRIVATE_GEN_SRC_DIR) -Iexternal/protobuf/src -I . \
        $(PROTO_FILES) \
    && touch $@
$(GEN_PROTO): $(HOST_OUT_EXECUTABLES)/aprotoc
	$(transform-generated-source)
LOCAL_GENERATED_SOURCES += $(GEN_PROTO)
GEN_PROTO:=

gen_src_dir:=

LOCAL_INIT_RC := incidentd.rc

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

LOCAL_SRC_FILES := $(call all-cpp-files-under, tests) \
    src/PrivacyBuffer.cpp \
    src/FdBuffer.cpp \
    src/Privacy.cpp \
    src/Reporter.cpp \
    src/Section.cpp \
    src/Throttler.cpp \
    src/incidentd_util.cpp \
    src/report_directory.cpp \

LOCAL_STATIC_LIBRARIES := \
    libgmock \

LOCAL_SHARED_LIBRARIES := \
    libbase \
    libbinder \
    libdebuggerd_client \
    libdumputils \
    libincident \
    liblog \
    libprotobuf-cpp-lite \
    libprotoutil \
    libservices \
    libutils \

LOCAL_TEST_DATA := $(call find-test-data-in-subdirs, $(LOCAL_PATH), *, testdata)

LOCAL_MODULE_CLASS := NATIVE_TESTS
gen_src_dir := $(local-generated-sources-dir)
# generate cppstream proto for testing
GEN_PROTO := $(gen_src_dir)/test.proto.timestamp
$(GEN_PROTO): $(HOST_OUT_EXECUTABLES)/aprotoc $(HOST_OUT_EXECUTABLES)/protoc-gen-cppstream $(PROTO_FILES)
$(GEN_PROTO): PRIVATE_GEN_SRC_DIR := $(gen_src_dir)
$(GEN_PROTO): PRIVATE_CUSTOM_TOOL = \
    $(HOST_OUT_EXECUTABLES)/aprotoc --plugin=protoc-gen-cppstream=$(HOST_OUT_EXECUTABLES)/protoc-gen-cppstream \
        --cppstream_out=$(PRIVATE_GEN_SRC_DIR) -Iexternal/protobuf/src -I . \
        $(PROTO_FILES) \
    && touch $@
$(GEN_PROTO): $(HOST_OUT_EXECUTABLES)/aprotoc
	$(transform-generated-source)
LOCAL_GENERATED_SOURCES += $(GEN_PROTO)
GEN_PROTO:=

gen_src_dir:=

include $(BUILD_NATIVE_TEST)
