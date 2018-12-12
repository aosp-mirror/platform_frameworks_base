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

LOCAL_PATH:= $(call my-dir)

# RollbackTestAppV1
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
LOCAL_MANIFEST_FILE := TestApp/AndroidManifestV1.xml
LOCAL_PACKAGE_NAME := RollbackTestAppV1
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := $(call all-java-files-under, TestApp/src)
include $(BUILD_PACKAGE)
ROLLBACK_TEST_APP_V1 := $(LOCAL_INSTALLED_MODULE)

# RollbackTestAppV2
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
LOCAL_MANIFEST_FILE := TestApp/AndroidManifestV2.xml
LOCAL_PACKAGE_NAME := RollbackTestAppV2
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := $(call all-java-files-under, TestApp/src)
include $(BUILD_PACKAGE)
ROLLBACK_TEST_APP_V2 := $(LOCAL_INSTALLED_MODULE)

# RollbackTest
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_PACKAGE_NAME := RollbackTest
LOCAL_MODULE_TAGS := tests
LOCAL_STATIC_JAVA_LIBRARIES := android-support-test
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_JAVA_RESOURCE_FILES := $(ROLLBACK_TEST_APP_V1) $(ROLLBACK_TEST_APP_V2)
LOCAL_SDK_VERSION := system_current
LOCAL_TEST_CONFIG := RollbackTest.xml
include $(BUILD_PACKAGE)

# Clean up local variables
ROLLBACK_TEST_APP_V1 :=
ROLLBACK_TEST_APP_V2 :=
