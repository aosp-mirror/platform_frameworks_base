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

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under,src)
LOCAL_PACKAGE_NAME := OverlayHostTests_UpdateOverlay
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_STATIC_JAVA_LIBRARIES := android-support-test
include $(BUILD_PACKAGE)

my_package_prefix := com.android.server.om.hosttest.framework_overlay

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := OverlayHostTests_FrameworkOverlayV1
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_CERTIFICATE := platform
LOCAL_AAPT_FLAGS := --custom-package $(my_package_prefix)_v1
LOCAL_AAPT_FLAGS += --version-code 1 --version-name v1
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/framework/v1/res
LOCAL_MANIFEST_FILE := framework/AndroidManifest.xml
include $(BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := OverlayHostTests_FrameworkOverlayV2
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_CERTIFICATE := platform
LOCAL_AAPT_FLAGS := --custom-package $(my_package_prefix)_v2
LOCAL_AAPT_FLAGS += --version-code 2 --version-name v2
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/framework/v2/res
LOCAL_MANIFEST_FILE := framework/AndroidManifest.xml
include $(BUILD_PACKAGE)

my_package_prefix := com.android.server.om.hosttest.app_overlay

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := OverlayHostTests_AppOverlayV1
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_CERTIFICATE := platform
LOCAL_AAPT_FLAGS := --custom-package $(my_package_prefix)_v1
LOCAL_AAPT_FLAGS += --version-code 1 --version-name v1
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/app/v1/res
LOCAL_MANIFEST_FILE := app/v1/AndroidManifest.xml
include $(BUILD_PACKAGE)

include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := tests
LOCAL_PACKAGE_NAME := OverlayHostTests_AppOverlayV2
LOCAL_SDK_VERSION := current
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_CERTIFICATE := platform
LOCAL_AAPT_FLAGS := --custom-package $(my_package_prefix)_v2
LOCAL_AAPT_FLAGS += --version-code 2 --version-name v2
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/app/v2/res
LOCAL_MANIFEST_FILE := app/v2/AndroidManifest.xml
include $(BUILD_PACKAGE)

my_package_prefix :=
