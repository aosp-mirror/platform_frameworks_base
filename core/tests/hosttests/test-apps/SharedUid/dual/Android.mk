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

# This makefile shows how to build a shared library and an activity that
# bundles the shared library and calls it using JNI.

TOP_LOCAL_PATH:= $(call my-dir)

# Build activity

LOCAL_PATH:= $(TOP_LOCAL_PATH)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := PMTest_Java_dual
LOCAL_MULTILIB := both
LOCAL_MODULE_TAGS := tests

LOCAL_JNI_SHARED_LIBRARIES = libpmtestdual

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_MANIFEST_FILE := dual/AndroidManifest.xml

LOCAL_SDK_VERSION := current
include $(BUILD_PACKAGE)

LOCAL_PATH:= $(TOP_LOCAL_PATH)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := PMTest_Java_multiarch
LOCAL_MULTILIB := both
LOCAL_MODULE_TAGS := tests

LOCAL_MANIFEST_FILE := multiarch/AndroidManifest.xml

LOCAL_JNI_SHARED_LIBRARIES = libpmtestdual

LOCAL_PROGUARD_ENABLED := disabled

LOCAL_SDK_VERSION := current
include $(BUILD_PACKAGE)

# ============================================================

# Also build all of the sub-targets under this one: the shared library.
include $(call all-makefiles-under,$(LOCAL_PATH))
