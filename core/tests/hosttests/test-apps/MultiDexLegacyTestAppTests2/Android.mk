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

LOCAL_PATH:= $(call my-dir)


## The tests with only one dex
include $(CLEAR_VARS)

LOCAL_STATIC_JAVA_LIBRARIES := android-support-multidex-instrumentation androidx.test.rules
LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_SDK_VERSION := current

LOCAL_PACKAGE_NAME := MultiDexLegacyTestAppTests2

LOCAL_DEX_PREOPT := false

LOCAL_JAVACFLAGS := -nowarn

LOCAL_MIN_SDK_VERSION := 8

LOCAL_INSTRUMENTATION_FOR := MultiDexLegacyTestApp

include $(BUILD_PACKAGE)


## The tests with a minimal main dex
include $(CLEAR_VARS)

LOCAL_STATIC_JAVA_LIBRARIES := android-support-multidex-instrumentation androidx.test.rules
LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_SDK_VERSION := 8

LOCAL_PACKAGE_NAME := MultiDexLegacyTestAppTests2-multidex

LOCAL_DEX_PREOPT := false

LOCAL_JAVACFLAGS := -nowarn

LOCAL_MIN_SDK_VERSION := 8

LOCAL_INSTRUMENTATION_FOR := MultiDexLegacyTestApp

LOCAL_JACK_FLAGS := -D jack.dex.output.policy=minimal-multidex -D jack.dex.output.multidex.legacy=true

include $(BUILD_PACKAGE)
