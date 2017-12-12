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
LOCAL_PATH := $(call my-dir)

# Library to perform dlopen on the actual shared library.
include $(CLEAR_VARS)
LOCAL_MODULE := libdvr_loader
LOCAL_MODULE_OWNER := google
LOCAL_SRC_FILES := dvr_library_loader.cpp
LOCAL_CFLAGS := -Wall -Werror
include $(BUILD_SHARED_LIBRARY)

# Java platform library for vr stuff.
include $(CLEAR_VARS)
LOCAL_MODULE := com.google.vr.platform
LOCAL_MODULE_OWNER := google
LOCAL_REQUIRED_MODULES := libdvr_loader libdvr
LOCAL_SRC_FILES := $(call all-java-files-under, java)
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := com.google.vr.platform.xml
LOCAL_SRC_FILES := com.google.vr.platform.xml
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_OWNER := google
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions
include $(BUILD_PREBUILT)
