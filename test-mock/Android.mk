#
# Copyright (C) 2008 The Android Open Source Project
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

LOCAL_PATH:= $(call my-dir)

# Includes the main framework source to ensure that doclava has access to the
# visibility information for the base classes of the mock classes. Without it
# otherwise hidden methods could be visible.
android_test_mock_source_files := \
    $(call all-java-files-under, src/android/test/mock) \
    $(call all-java-files-under, ../core/java/android)

# For unbundled build we'll use the prebuilt jar from prebuilts/sdk.
ifeq (,$(TARGET_BUILD_APPS)$(filter true,$(TARGET_BUILD_PDK)))

# Generate the stub source files for android.test.mock.stubs
# ==========================================================
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(android_test_mock_source_files)
LOCAL_JAVA_LIBRARIES := core-oj core-libart framework conscrypt okhttp bouncycastle
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_DROIDDOC_SOURCE_PATH := $(LOCAL_PATH)/src/android/test/mock

ANDROID_TEST_MOCK_OUTPUT_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.stubs_intermediates/api.txt
ANDROID_TEST_MOCK_OUTPUT_REMOVED_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.stubs_intermediates/removed.txt

ANDROID_TEST_MOCK_API_FILE := $(LOCAL_PATH)/api/android-test-mock-current.txt
ANDROID_TEST_MOCK_REMOVED_API_FILE := $(LOCAL_PATH)/api/android-test-mock-removed.txt

LOCAL_DROIDDOC_OPTIONS:= \
    -hide 111 -hide 113 -hide 125 -hide 126 -hide 127 -hide 128 \
    -stubpackages android.test.mock \
    -stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.stubs_intermediates/src \
    -nodocs \
    -api $(ANDROID_TEST_MOCK_OUTPUT_API_FILE) \
    -removedApi $(ANDROID_TEST_MOCK_OUTPUT_REMOVED_API_FILE) \

LOCAL_UNINSTALLABLE_MODULE := true
LOCAL_MODULE := android-test-mock-api-stubs-gen

include $(BUILD_DROIDDOC)

# Remember the target that will trigger the code generation.
android_test_mock_gen_stamp := $(full_target)

# Add some additional dependencies
$(ANDROID_TEST_MOCK_OUTPUT_API_FILE): $(full_target)
$(ANDROID_TEST_MOCK_OUTPUT_REMOVED_API_FILE): $(full_target)

# Build the android.test.mock.stubs library
# =========================================
include $(CLEAR_VARS)

LOCAL_MODULE := android.test.mock.stubs

LOCAL_SOURCE_FILES_ALL_GENERATED := true

# Make sure to run droiddoc first to generate the stub source files.
LOCAL_ADDITIONAL_DEPENDENCIES := $(android_test_mock_gen_stamp)
android_test_mock_gen_stamp :=

LOCAL_SDK_VERSION := current

include $(BUILD_STATIC_JAVA_LIBRARY)

# Archive a copy of the classes.jar in SDK build.
$(call dist-for-goals,sdk win_sdk,$(full_classes_jar):android.test.mock.stubs.jar)

# Check that the android.test.mock.stubs library has not changed
# ==============================================================

# Check that the API we're building hasn't changed from the not-yet-released
# SDK version.
$(eval $(call check-api, \
    check-android-test-mock-api-current, \
    $(ANDROID_TEST_MOCK_API_FILE), \
    $(ANDROID_TEST_MOCK_OUTPUT_API_FILE), \
    $(ANDROID_TEST_MOCK_REMOVED_API_FILE), \
    $(ANDROID_TEST_MOCK_OUTPUT_REMOVED_API_FILE), \
    -error 2 -error 3 -error 4 -error 5 -error 6 \
    -error 7 -error 8 -error 9 -error 10 -error 11 -error 12 -error 13 -error 14 -error 15 \
    -error 16 -error 17 -error 18 -error 19 -error 20 -error 21 -error 23 -error 24 \
    -error 25 -error 26 -error 27, \
    cat $(LOCAL_PATH)/api/apicheck_msg_android_test_mock.txt, \
    check-android-test-mock-api, \
    $(call doc-timestamp-for,android-test-mock-api-stubs-gen) \
    ))

.PHONY: check-android-test-mock-api
checkapi: check-android-test-mock-api

.PHONY: update-android-test-mock-api
update-api: update-android-test-mock-api

update-android-test-mock-api: $(ANDROID_TEST_MOCK_OUTPUT_API_FILE) | $(ACP)
	@echo Copying current.txt
	$(hide) $(ACP) $(ANDROID_TEST_MOCK_OUTPUT_API_FILE) $(ANDROID_TEST_MOCK_API_FILE)
	@echo Copying removed.txt
	$(hide) $(ACP) $(ANDROID_TEST_MOCK_OUTPUT_REMOVED_API_FILE) $(ANDROID_TEST_MOCK_REMOVED_API_FILE)

# Generate the stub source files for android.test.mock.stubs-system
# =================================================================
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(android_test_mock_source_files)

LOCAL_JAVA_LIBRARIES := core-oj core-libart framework conscrypt okhttp bouncycastle
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_DROIDDOC_SOURCE_PATH := $(LOCAL_PATH)/src/android/test/mock

ANDROID_TEST_MOCK_SYSTEM_OUTPUT_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.stubs-system_intermediates/api.txt
ANDROID_TEST_MOCK_SYSTEM_OUTPUT_REMOVED_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.stubs-system_intermediates/removed.txt

ANDROID_TEST_MOCK_SYSTEM_API_FILE := $(LOCAL_PATH)/api/android-test-mock-system-current.txt
ANDROID_TEST_MOCK_SYSTEM_REMOVED_API_FILE := $(LOCAL_PATH)/api/android-test-mock-system-removed.txt

LOCAL_DROIDDOC_OPTIONS:= \
    -stubpackages android.test.mock \
    -stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.stubs-system_intermediates/src \
    -nodocs \
    -showAnnotation android.annotation.SystemApi \
    -api $(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_API_FILE) \
    -removedApi $(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_REMOVED_API_FILE) \

LOCAL_UNINSTALLABLE_MODULE := true
LOCAL_MODULE := android-test-mock-system-api-stubs-gen

include $(BUILD_DROIDDOC)

# Remember the target that will trigger the code generation.
android_test_mock_system_gen_stamp := $(full_target)

# Add some additional dependencies
$(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_API_FILE): $(full_target)
$(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_REMOVED_API_FILE): $(full_target)

# Build the android.test.mock.stubs-system library
# ================================================
include $(CLEAR_VARS)

LOCAL_MODULE := android.test.mock.stubs-system

LOCAL_SOURCE_FILES_ALL_GENERATED := true

# Make sure to run droiddoc first to generate the stub source files.
LOCAL_ADDITIONAL_DEPENDENCIES := $(android_test_mock_system_gen_stamp)
android_test_mock_system_gen_stamp :=

LOCAL_SDK_VERSION := system_current

include $(BUILD_STATIC_JAVA_LIBRARY)

# Archive a copy of the classes.jar in SDK build.
$(call dist-for-goals,sdk win_sdk,$(full_classes_jar):android.test.mock.stubs_system.jar)

# Check that the android.test.mock.stubs-system library has not changed
# =====================================================================

# Check that the API we're building hasn't changed from the not-yet-released
# SDK version.
$(eval $(call check-api, \
    check-android-test-mock-system-api-current, \
    $(ANDROID_TEST_MOCK_SYSTEM_API_FILE), \
    $(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_API_FILE), \
    $(ANDROID_TEST_MOCK_SYSTEM_REMOVED_API_FILE), \
    $(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_REMOVED_API_FILE), \
    -error 2 -error 3 -error 4 -error 5 -error 6 \
    -error 7 -error 8 -error 9 -error 10 -error 11 -error 12 -error 13 -error 14 -error 15 \
    -error 16 -error 17 -error 18 -error 19 -error 20 -error 21 -error 23 -error 24 \
    -error 25 -error 26 -error 27, \
    cat $(LOCAL_PATH)/api/apicheck_msg_android_test_mock-system.txt, \
    check-android-test-mock-system-api, \
    $(call doc-timestamp-for,android-test-mock-system-api-stubs-gen) \
    ))

.PHONY: check-android-test-mock-system-api
checkapi: check-android-test-mock-system-api

.PHONY: update-android-test-mock-system-api
update-api: update-android-test-mock-system-api

update-android-test-mock-system-api: $(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_API_FILE) | $(ACP)
	@echo Copying current.txt
	$(hide) $(ACP) $(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_API_FILE) $(ANDROID_TEST_MOCK_SYSTEM_API_FILE)
	@echo Copying removed.txt
	$(hide) $(ACP) $(ANDROID_TEST_MOCK_SYSTEM_OUTPUT_REMOVED_API_FILE) $(ANDROID_TEST_MOCK_SYSTEM_REMOVED_API_FILE)

endif  # not TARGET_BUILD_APPS not TARGET_BUILD_PDK=true

