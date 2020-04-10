# Copyright (C) 2018 The Android Open Source Project
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

my_package_prefix := com.android.server.om.hosttest.signature_overlay

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := OverlayHostTests_NonPlatformSignatureOverlay
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_AAPT_FLAGS := --custom-package $(my_package_prefix)_bad
include $(BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := OverlayHostTests_PlatformSignatureStaticOverlay
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_CERTIFICATE := platform
LOCAL_MANIFEST_FILE := static/AndroidManifest.xml
LOCAL_AAPT_FLAGS := --custom-package $(my_package_prefix)_static
include $(BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := OverlayHostTests_PlatformSignatureOverlay
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_CERTIFICATE := platform
LOCAL_AAPT_FLAGS := --custom-package $(my_package_prefix)_v1
LOCAL_AAPT_FLAGS += --version-code 1 --version-name v1
include $(BUILD_PACKAGE)

my_package_prefix :=
