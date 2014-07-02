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

ifneq ($(TARGET_BUILD_JAVA_SUPPORT_LEVEL),)

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_PROGUARD_ENABLED := disabled

# comment it out for now since we need use some hidden APIs
LOCAL_SDK_VERSION := current

LOCAL_STATIC_JAVA_LIBRARIES := android-ex-camera2

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src) \
    $(call all-renderscript-files-under, src)

LOCAL_PACKAGE_NAME := SmartCamera
LOCAL_JNI_SHARED_LIBRARIES := libsmartcamera_jni

include $(BUILD_PACKAGE)

# Include packages in subdirectories
include $(call all-makefiles-under,$(LOCAL_PATH))

endif
