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

ifneq (,$(findstring lowpan/java,$(FRAMEWORKS_BASE_SUBDIRS)))
include $(CLEAR_VARS)
LOCAL_MODULE := libandroid_net_lowpan
LOCAL_MODULE_TAGS := optional
LOCAL_SHARED_LIBRARIES += libbase
LOCAL_SHARED_LIBRARIES += libbinder
LOCAL_SHARED_LIBRARIES += libutils
LOCAL_AIDL_INCLUDES += frameworks/native/aidl/binder
LOCAL_AIDL_INCLUDES += frameworks/base/lowpan/java
LOCAL_AIDL_INCLUDES += frameworks/base/core/java
LOCAL_SRC_FILES += $(call all-Iaidl-files-under, java/android/net/lowpan)
include $(BUILD_SHARED_LIBRARY)
endif
