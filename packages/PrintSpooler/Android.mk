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

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res frameworks/support/v7/recyclerview/res
LOCAL_AAPT_FLAGS := --auto-add-overlay --extra-packages android.support.v7.recyclerview
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SRC_FILES += \
        src/com/android/printspooler/renderer/IPdfRenderer.aidl \
        src/com/android/printspooler/renderer/IPdfEditor.aidl

LOCAL_PACKAGE_NAME := PrintSpooler

LOCAL_JNI_SHARED_LIBRARIES := libprintspooler_jni
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4 android-support-v7-recyclerview

include $(BUILD_PACKAGE)

include $(call all-makefiles-under, $(LOCAL_PATH))
