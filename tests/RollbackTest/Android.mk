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

# RollbackTestAppAv1.apk
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := $(call all-java-files-under, TestApp/src)
LOCAL_MANIFEST_FILE := TestApp/Av1.xml
LOCAL_PACKAGE_NAME := RollbackTestAppAv1
include $(BUILD_PACKAGE)
ROLLBACK_TEST_APP_AV1 := $(LOCAL_INSTALLED_MODULE)

# RollbackTestAppAv2.apk
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := $(call all-java-files-under, TestApp/src)
LOCAL_MANIFEST_FILE := TestApp/Av2.xml
LOCAL_PACKAGE_NAME := RollbackTestAppAv2
include $(BUILD_PACKAGE)
ROLLBACK_TEST_APP_AV2 := $(LOCAL_INSTALLED_MODULE)

# RollbackTestAppACrashingV2.apk
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := $(call all-java-files-under, TestApp/src)
LOCAL_MANIFEST_FILE := TestApp/ACrashingV2.xml
LOCAL_PACKAGE_NAME := RollbackTestAppACrashingV2
include $(BUILD_PACKAGE)
ROLLBACK_TEST_APP_A_CRASHING_V2 := $(LOCAL_INSTALLED_MODULE)

# RollbackTestAppBv1.apk
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := $(call all-java-files-under, TestApp/src)
LOCAL_MANIFEST_FILE := TestApp/Bv1.xml
LOCAL_PACKAGE_NAME := RollbackTestAppBv1
include $(BUILD_PACKAGE)
ROLLBACK_TEST_APP_BV1 := $(LOCAL_INSTALLED_MODULE)

# RollbackTestAppBv2.apk
include $(CLEAR_VARS)
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA_APPS)
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := $(call all-java-files-under, TestApp/src)
LOCAL_MANIFEST_FILE := TestApp/Bv2.xml
LOCAL_PACKAGE_NAME := RollbackTestAppBv2
include $(BUILD_PACKAGE)
ROLLBACK_TEST_APP_BV2 := $(LOCAL_INSTALLED_MODULE)

# RollbackTest
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-java-files-under, RollbackTest/src)
LOCAL_PACKAGE_NAME := RollbackTest
LOCAL_MODULE_TAGS := tests
LOCAL_STATIC_JAVA_LIBRARIES := android-support-test
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_COMPATIBILITY_SUPPORT_FILES := $(ROLLBACK_TEST_APEX_V1)
LOCAL_JAVA_RESOURCE_FILES := \
  $(ROLLBACK_TEST_APP_AV1) \
  $(ROLLBACK_TEST_APP_AV2) \
  $(ROLLBACK_TEST_APP_A_CRASHING_V2) \
  $(ROLLBACK_TEST_APP_BV1) \
  $(ROLLBACK_TEST_APP_BV2) \
  $(ROLLBACK_TEST_APEX_V2)
LOCAL_MANIFEST_FILE := RollbackTest/AndroidManifest.xml
LOCAL_SDK_VERSION := system_current
LOCAL_TEST_CONFIG := RollbackTest.xml
include $(BUILD_PACKAGE)

# StagedRollbackTest
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-java-files-under, StagedRollbackTest/src)
LOCAL_MODULE := StagedRollbackTest
LOCAL_MODULE_TAGS := tests
LOCAL_JAVA_LIBRARIES := tradefed
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_TEST_CONFIG := StagedRollbackTest.xml
include $(BUILD_HOST_JAVA_LIBRARY)

# Clean up local variables
ROLLBACK_TEST_APP_AV1 :=
ROLLBACK_TEST_APP_AV2 :=
ROLLBACK_TEST_APP_A_CRASHING_V2 :=
ROLLBACK_TEST_APP_BV1 :=
ROLLBACK_TEST_APP_BV2 :=
