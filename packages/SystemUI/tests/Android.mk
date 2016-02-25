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

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_JACK_FLAGS := --multi-dex native

LOCAL_PROTOC_OPTIMIZE_TYPE := nano
LOCAL_PROTOC_FLAGS := -I$(LOCAL_PATH)/..
LOCAL_PROTO_JAVA_OUTPUT_PARAMS := optional_field_style=accessors

LOCAL_AAPT_FLAGS := --auto-add-overlay \
    --extra-packages com.android.systemui:com.android.keyguard:android.support.v14.preference:android.support.v7.preference:android.support.v7.appcompat:android.support.v7.recyclerview \
    --extra-packages android.support.v17.leanback

LOCAL_SRC_FILES := $(call all-java-files-under, src) \
    $(call all-Iaidl-files-under, src) \
    $(call all-java-files-under, ../src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res \
    frameworks/support/v7/preference/res \
    frameworks/support/v14/preference/res \
    frameworks/support/v7/appcompat/res \
    frameworks/support/v7/recyclerview/res \
    frameworks/support/v17/leanback/res \
    frameworks/base/packages/SystemUI/res \
    frameworks/base/packages/Keyguard/res

LOCAL_JAVA_LIBRARIES := android.test.runner telephony-common

LOCAL_PACKAGE_NAME := SystemUITests

LOCAL_STATIC_JAVA_LIBRARIES := \
    mockito-target \
    Keyguard \
    android-support-v7-recyclerview \
    android-support-v7-preference \
    android-support-v7-appcompat \
    android-support-v14-preference \
    android-support-v17-leanback \
    SystemUI-proto-tags

# sign this with platform cert, so this test is allowed to inject key events into
# UI it doesn't own. This is necessary to allow screenshots to be taken
LOCAL_CERTIFICATE := platform

include frameworks/base/packages/SettingsLib/common.mk

include $(BUILD_PACKAGE)
