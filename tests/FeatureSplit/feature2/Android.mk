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

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := FeatureSplit2
LOCAL_MODULE_TAGS := tests

featureOf := FeatureSplitBase
featureAfter := FeatureSplit1

LOCAL_APK_LIBRARIES := $(featureOf)

featureOfApk := $(call intermediates-dir-for,APPS,$(featureOf))/package.apk
featureAfterApk := $(call intermediates-dir-for,APPS,$(featureAfter))/package.apk
localRStamp := $(call intermediates-dir-for,APPS,$(LOCAL_PACKAGE_NAME),,COMMON)/src/R.stamp
$(localRStamp): $(featureOfApk) $(featureAfterApk)

LOCAL_AAPT_FLAGS := --feature-of $(featureOfApk)
LOCAL_AAPT_FLAGS += --feature-after $(featureAfterApk)
LOCAL_AAPT_FLAGS += --custom-package com.android.test.split.feature.two

include $(BUILD_PACKAGE)
