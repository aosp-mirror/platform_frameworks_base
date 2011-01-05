#
# Copyright (C) 2010 The Android Open Source Project
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

LOCAL_SRC_FILES := \
    src/MimeTypeUtil.cpp

LOCAL_MODULE := libdrmutility

LOCAL_SHARED_LIBRARIES :=  \
    libutils \
    libdl \
    libdvm \
    libandroid_runtime \
    libnativehelper \
    liblog


base := frameworks/base

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    $(base)/include \
    $(base)/include/drm \
    $(base)/include/drm/plugins \
    $(LOCAL_PATH)/include


ifneq ($(TARGET_BUILD_VARIANT),user)
LOCAL_C_INCLUDES += \
    $(LOCAL_PATH)/tools

endif

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)
