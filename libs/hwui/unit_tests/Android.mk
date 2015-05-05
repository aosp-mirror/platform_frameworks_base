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

local_target_dir := $(TARGET_OUT_DATA)/local/tmp
LOCAL_PATH:= $(call my-dir)/..

include $(CLEAR_VARS)

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.common.mk
LOCAL_MODULE := hwui_unit_tests
LOCAL_MODULE_TAGS := tests

include $(LOCAL_PATH)/Android.common.mk

LOCAL_SRC_FILES += \
    unit_tests/ClipAreaTests.cpp \
    unit_tests/DamageAccumulatorTests.cpp \
    unit_tests/LinearAllocatorTests.cpp \
    unit_tests/main.cpp


include $(BUILD_NATIVE_TEST)
