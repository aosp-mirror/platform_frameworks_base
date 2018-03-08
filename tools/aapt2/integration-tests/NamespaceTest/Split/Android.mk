#
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
#

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_USE_AAPT2 := true
LOCAL_AAPT_NAMESPACES := true
LOCAL_PACKAGE_NAME := AaptTestNamespace_Split
LOCAL_SDK_VERSION := current
LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under,src)
LOCAL_APK_LIBRARIES := AaptTestNamespace_App
LOCAL_RES_LIBRARIES := AaptTestNamespace_App
LOCAL_AAPT_FLAGS := --package-id 0x80 --rename-manifest-package com.android.aapt.namespace.app
include $(BUILD_PACKAGE)
