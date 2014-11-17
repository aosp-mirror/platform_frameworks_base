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

# ==========================================================
# Setup some common variables for the different build
# targets here.
# ==========================================================
LOCAL_PATH:= $(call my-dir)
testFiles := \
    AttributeFinder_test.cpp \
    ByteBucketArray_test.cpp \
    Config_test.cpp \
    ConfigLocale_test.cpp \
    Idmap_test.cpp \
    ResTable_test.cpp \
    Split_test.cpp \
    TestHelpers.cpp \
    Theme_test.cpp \
    TypeWrappers_test.cpp \
    ZipUtils_test.cpp

# ==========================================================
# Build the host tests: libandroidfw_tests
# ==========================================================
include $(CLEAR_VARS)

LOCAL_MODULE := libandroidfw_tests
LOCAL_SRC_FILES := $(testFiles)
LOCAL_STATIC_LIBRARIES := \
    libandroidfw \
    libutils \
    libcutils \
	liblog

include $(BUILD_HOST_NATIVE_TEST)


# ==========================================================
# Build the device tests: libandroidfw_tests
# ==========================================================
ifneq ($(SDK_ONLY),true)
include $(CLEAR_VARS)

LOCAL_MODULE := libandroidfw_tests
LOCAL_SRC_FILES := $(testFiles) \
    BackupData_test.cpp \
    ObbFile_test.cpp
LOCAL_SHARED_LIBRARIES := \
    libandroidfw \
    libcutils \
    libutils \
    libui \
    libstlport

include $(BUILD_NATIVE_TEST)
endif # Not SDK_ONLY

