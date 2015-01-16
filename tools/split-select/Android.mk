#
# Copyright (C) 2014 The Android Open Source Project
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

# This tool is prebuilt if we're doing an app-only build.
ifeq ($(TARGET_BUILD_APPS)$(filter true,$(TARGET_BUILD_PDK)),)

# ==========================================================
# Setup some common variables for the different build
# targets here.
# ==========================================================
LOCAL_PATH:= $(call my-dir)

main := Main.cpp
sources := \
    Abi.cpp \
    Grouper.cpp \
    Rule.cpp \
    RuleGenerator.cpp \
    SplitDescription.cpp \
    SplitSelector.cpp

testSources := \
    Grouper_test.cpp \
    Rule_test.cpp \
    RuleGenerator_test.cpp \
    SplitSelector_test.cpp \
    TestRules.cpp

cIncludes := \
    external/zlib \
    frameworks/base/tools

hostLdLibs :=
hostStaticLibs := \
    libaapt \
    libandroidfw \
    libpng \
    liblog \
    libutils \
    libcutils \
    libexpat \
    libziparchive-host

cFlags := -Wall -Werror

ifeq ($(HOST_OS),linux)
    hostLdLibs += -lrt -ldl -lpthread
endif

# Statically link libz for MinGW (Win SDK under Linux),
# and dynamically link for all others.
ifneq ($(strip $(USE_MINGW)),)
    hostStaticLibs += libz
else
    hostLdLibs += -lz
endif


# ==========================================================
# Build the host static library: libsplit-select
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libsplit-select

LOCAL_SRC_FILES := $(sources)

LOCAL_C_INCLUDES += $(cIncludes)
LOCAL_CFLAGS += $(cFlags) -D_DARWIN_UNLIMITED_STREAMS

include $(BUILD_HOST_STATIC_LIBRARY)


# ==========================================================
# Build the host tests: libsplit-select_tests
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := libsplit-select_tests
LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(testSources)

LOCAL_C_INCLUDES += $(cIncludes)
LOCAL_STATIC_LIBRARIES += libsplit-select $(hostStaticLibs)
LOCAL_LDLIBS += $(hostLdLibs)
LOCAL_CFLAGS += $(cFlags)

include $(BUILD_HOST_NATIVE_TEST)

# ==========================================================
# Build the host executable: split-select
# ==========================================================
include $(CLEAR_VARS)
LOCAL_MODULE := split-select

LOCAL_SRC_FILES := $(main)

LOCAL_C_INCLUDES += $(cIncludes)
LOCAL_STATIC_LIBRARIES += libsplit-select $(hostStaticLibs)
LOCAL_LDLIBS += $(hostLdLibs)
LOCAL_CFLAGS += $(cFlags)

include $(BUILD_HOST_EXECUTABLE)

endif # No TARGET_BUILD_APPS or TARGET_BUILD_PDK
