# Copyright (C) 2015 The Android Open Source Project
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

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := platform

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.test.runner telephony-common android.test.base

LOCAL_JACK_FLAGS := --multi-dex native

LOCAL_PACKAGE_NAME := SettingsLibTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_USE_AAPT2 := true

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.test.rules \
    androidx.test.espresso.core \
    mockito-target-minus-junit4 \
    truth-prebuilt

# Code coverage puts us over the dex limit, so enable multi-dex for coverage-enabled builds
ifeq (true,$(EMMA_INSTRUMENT))
LOCAL_JACK_FLAGS := --multi-dex native
LOCAL_DX_FLAGS := --multi-dex
endif # EMMA_INSTRUMENT

include frameworks/base/packages/SettingsLib/common.mk

include $(BUILD_PACKAGE)
