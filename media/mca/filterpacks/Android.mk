# Copyright (C) 2011 The Android Open Source Project
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

##
# base
##
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_MODULE := libfilterpack_base
LOCAL_SRC_FILES := native/base/geometry.cpp \
                   native/base/time_util.cpp

LOCAL_CFLAGS := -DANDROID

include external/stlport/libstlport.mk

include $(BUILD_STATIC_LIBRARY)

##
# filterpack_imageproc
##
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_MODULE := libfilterpack_imageproc

LOCAL_SRC_FILES += native/imageproc/brightness.c \
                   native/imageproc/contrast.c \
                   native/imageproc/invert.c \
                   native/imageproc/to_rgba.c

LOCAL_SHARED_LIBRARIES := libutils libfilterfw

LOCAL_PRELINK_MODULE := false

include $(BUILD_SHARED_LIBRARY)


