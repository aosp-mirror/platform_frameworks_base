#
# Copyright (C) 2016 The Android Open Source Project
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

###########################################################
# Variant: Privileged app

include $(CLEAR_VARS)

LOCAL_MODULE := CtsShimPrivPrebuilt
LOCAL_MODULE_TAGS := optional
# this needs to be a privileged application
LOCAL_PRIVILEGED_MODULE := true
LOCAL_MODULE_CLASS := APPS
LOCAL_BUILT_MODULE_STEM := package.apk
# Make sure the build system doesn't try to resign the APK
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_DEX_PREOPT := false
LOCAL_MODULE_TARGET_ARCH := arm arm64 x86 x86_64

LOCAL_SRC_FILES_arm := apk/arm/CtsShimPriv.apk
LOCAL_SRC_FILES_arm64 := apk/arm/CtsShimPriv.apk
LOCAL_SRC_FILES_x86 := apk/x86/CtsShimPriv.apk
LOCAL_SRC_FILES_x86_64 := apk/x86/CtsShimPriv.apk

include $(BUILD_PREBUILT)


###########################################################
# Variant: System app

include $(CLEAR_VARS)

LOCAL_MODULE := CtsShimPrebuilt
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := APPS
LOCAL_BUILT_MODULE_STEM := package.apk
# Make sure the build system doesn't try to resign the APK
LOCAL_CERTIFICATE := PRESIGNED
LOCAL_DEX_PREOPT := false
LOCAL_MODULE_TARGET_ARCH := arm arm64 x86 x86_64

LOCAL_SRC_FILES_arm := apk/arm/CtsShim.apk
LOCAL_SRC_FILES_arm64 := apk/arm/CtsShim.apk
LOCAL_SRC_FILES_x86 := apk/x86/CtsShim.apk
LOCAL_SRC_FILES_x86_64 := apk/x86/CtsShim.apk

include $(BUILD_PREBUILT)

include $(call all-makefiles-under,$(LOCAL_PATH))
