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

LOCAL_SRC_FILES:= \
    DrmConstraints.cpp \
    DrmMetadata.cpp \
    DrmConvertedStatus.cpp \
    DrmEngineBase.cpp \
    DrmInfo.cpp \
    DrmInfoRequest.cpp \
    DrmInfoStatus.cpp \
    DrmRights.cpp \
    DrmSupportInfo.cpp \
    IDrmManagerService.cpp \
    IDrmServiceListener.cpp \
    DrmInfoEvent.cpp \
    ReadWriteUtils.cpp

LOCAL_C_INCLUDES := \
    $(TOP)/frameworks/base/include \
    $(TOP)/frameworks/base/drm/libdrmframework/include \
    $(TOP)/frameworks/base/drm/libdrmframework/plugins/common/include

LOCAL_MODULE:= libdrmframeworkcommon

LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)
