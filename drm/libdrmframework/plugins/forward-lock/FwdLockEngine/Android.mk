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
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

# The flag below turns on local debug printouts
#LOCAL_CFLAGS += -DDRM_OMA_FL_ENGINE_DEBUG

base := frameworks/base

# Determine whether the DRM framework uses 64-bit data types for file offsets and do the same.
ifneq ($(shell grep -c 'off64_t offset' $(base)/drm/libdrmframework/plugins/common/include/IDrmEngine.h), 0)
LOCAL_CFLAGS += -DUSE_64BIT_DRM_API
endif

LOCAL_SRC_FILES:= \
    src/FwdLockEngine.cpp

LOCAL_MODULE := libfwdlockengine

LOCAL_SHARED_LIBRARIES := \
    libicui18n \
    libicuuc \
    libutils \
    libdl \
    libandroid_runtime \
    libnativehelper \
    libcrypto \
    libssl \
    libdrmframework

LOCAL_STATIC_LIBRARIES := \
    libdrmutility \
    libdrmframeworkcommon \
    libfwdlock-common \
    libfwdlock-converter \
    libfwdlock-decoder



LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    $(base)/include/drm \
    $(base)/drm/libdrmframework/plugins/common/include \
    $(base)/drm/libdrmframework/plugins/common/util/include \
    $(base)/drm/libdrmframework/plugins/forward-lock/internal-format/common \
    $(base)/drm/libdrmframework/plugins/forward-lock/internal-format/converter \
    $(base)/drm/libdrmframework/plugins/forward-lock/internal-format/decoder \
    $(LOCAL_PATH)/include \
    external/openssl/include

LOCAL_MODULE_PATH := $(TARGET_OUT_SHARED_LIBRARIES)/drm

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
