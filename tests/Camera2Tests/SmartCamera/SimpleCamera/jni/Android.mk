# Copyright (C) 2013 The Android Open Source Project
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

FILTERFW_NATIVE_PATH := $(call my-dir)


#
# Build module libfilterframework
#
LOCAL_PATH := $(FILTERFW_NATIVE_PATH)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SDK_VERSION := 14

LOCAL_MODULE := libsmartcamera_jni

LOCAL_SRC_FILES := contrast.cpp \
                brightness.cpp \
                exposure.cpp \
                colorspace.cpp \
                histogram.cpp \
                frametovalues.cpp \
                pixelutils.cpp \
                sobeloperator.cpp \
                stats_scorer.cpp

LOCAL_CFLAGS += -Wall -Wextra -Werror -Wno-unused-parameter

LOCAL_NDK_STL_VARIANT := c++_static

include $(BUILD_SHARED_LIBRARY)
