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

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := FeatureSplit2
LOCAL_SDK_VERSION := current
LOCAL_MODULE_TAGS := tests

LOCAL_APK_LIBRARIES := FeatureSplitBase
LOCAL_RES_LIBRARIES := FeatureSplitBase

LOCAL_AAPT_FLAGS += --package-id 0x81
LOCAL_AAPT_FLAGS += --custom-package com.android.test.split.feature.two

include $(BUILD_PACKAGE)
