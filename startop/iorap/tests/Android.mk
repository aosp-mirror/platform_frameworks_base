# Copyright (C) 2018 The Android Open Source Project
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

# android_test does not support JNI libraries
# TODO: once b/80095087 is fixed, rewrite this back to android_test
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_JACK_FLAGS := --multi-dex native
LOCAL_DX_FLAGS := --multi-dex

LOCAL_PACKAGE_NAME := libiorap-java-tests
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_STATIC_JAVA_LIBRARIES := \
    libiorap-java-test-lib

LOCAL_MULTILIB := both

LOCAL_JNI_SHARED_LIBRARIES := \
    libdexmakerjvmtiagent \
    libstaticjvmtiagent \
    libmultiplejvmtiagentsinterferenceagent

LOCAL_JAVA_LIBRARIES := \
    android.test.base \
    android.test.runner

# Use private APIs
LOCAL_CERTIFICATE := platform
LOCAL_PRIVATE_PLATFORM_APIS := true

# Disable presubmit test until it works with disabled iorap by default.
LOCAL_PRESUBMIT_DISABLED := true

include $(BUILD_PACKAGE)
