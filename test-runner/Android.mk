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

android_test_mock_source_files := $(call all-java-files-under, src/android/test/mock)

# Build the android.test.runner library
# =====================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    $(filter-out $(android_test_mock_source_files), $(call all-java-files-under, src))

LOCAL_JAVA_LIBRARIES := \
    core-oj \
    core-libart \
    framework \
    legacy-test \
    android.test.mock \

LOCAL_MODULE:= android.test.runner

include $(BUILD_JAVA_LIBRARY)

# Build the repackaged.android.test.runner library
# ================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := core-oj core-libart framework legacy-test

LOCAL_JARJAR_RULES := $(LOCAL_PATH)/../legacy-test/jarjar-rules.txt

LOCAL_MODULE:= repackaged.android.test.runner

include $(BUILD_STATIC_JAVA_LIBRARY)

# Generate the stub source files for android.test.runner.stubs
# ============================================================
include $(CLEAR_VARS)

# Exclude android.test.mock classes as stubs for them are created in the
# android.test.mock.stubs target
LOCAL_SRC_FILES := \
    $(filter-out $(android_test_mock_source_files), $(call all-java-files-under, src))

LOCAL_JAVA_LIBRARIES := \
    core-oj \
    core-libart \
    framework \
    legacy-test \
    android.test.mock \

LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_DROIDDOC_SOURCE_PATH := $(LOCAL_PATH)/src

ANDROID_TEST_RUNNER_OUTPUT_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.runner.stubs_intermediates/api.txt
ANDROID_TEST_RUNNER_OUTPUT_REMOVED_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.runner.stubs_intermediates/removed.txt

ANDROID_TEST_RUNNER_API_FILE := $(LOCAL_PATH)/api/android-test-runner-current.txt
ANDROID_TEST_RUNNER_REMOVED_API_FILE := $(LOCAL_PATH)/api/android-test-runner-removed.txt

LOCAL_DROIDDOC_OPTIONS:= \
    -stubpackages android.test:android.test.suitebuilder:junit.runner:junit.textui \
    -stubsourceonly \
    -stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.runner.stubs_intermediates/src \
    -nodocs \
    -api $(ANDROID_TEST_RUNNER_OUTPUT_API_FILE) \
    -removedApi $(ANDROID_TEST_RUNNER_OUTPUT_REMOVED_API_FILE) \

LOCAL_UNINSTALLABLE_MODULE := true
LOCAL_MODULE := android-test-runner-api-stubs-gen

include $(BUILD_DROIDDOC)

# Remember the target that will trigger the code generation.
android_test_runner_api_gen_stamp := $(full_target)

# Add some additional dependencies
$(ANDROID_TEST_RUNNER_OUTPUT_API_FILE): $(full_target)
$(ANDROID_TEST_RUNNER_OUTPUT_REMOVED_API_FILE): $(full_target)

# Build the android.test.runner.stubs library
# ===========================================
include $(CLEAR_VARS)

LOCAL_MODULE := android.test.runner.stubs

LOCAL_JAVA_LIBRARIES := \
    legacy.test.stubs \
    android.test.mock.stubs \

LOCAL_SOURCE_FILES_ALL_GENERATED := true

include $(BUILD_STATIC_JAVA_LIBRARY)

# Make sure to run droiddoc first to generate the stub source files.
$(full_classes_compiled_jar) : $(android_test_runner_api_gen_stamp)
$(full_classes_jack) : $(android_test_runner_api_gen_stamp)

# Archive a copy of the classes.jar in SDK build.
$(call dist-for-goals,sdk win_sdk,$(full_classes_jar):android.test.runner.stubs.jar)

# Check that the android.test.runner.stubs library has not changed
# ================================================================

# Check that the API we're building hasn't changed from the not-yet-released
# SDK version.
$(eval $(call check-api, \
    check-android-test-runner-api-current, \
    $(ANDROID_TEST_RUNNER_API_FILE), \
    $(ANDROID_TEST_RUNNER_OUTPUT_API_FILE), \
    $(ANDROID_TEST_RUNNER_REMOVED_API_FILE), \
    $(ANDROID_TEST_RUNNER_OUTPUT_REMOVED_API_FILE), \
    -error 2 -error 3 -error 4 -error 5 -error 6 \
    -error 7 -error 8 -error 9 -error 10 -error 11 -error 12 -error 13 -error 14 -error 15 \
    -error 16 -error 17 -error 18 -error 19 -error 20 -error 21 -error 23 -error 24 \
    -error 25 -error 26 -error 27, \
    cat $(LOCAL_PATH)/api/apicheck_msg_android_test_runner.txt, \
    check-android-test-runner-api, \
    $(call doc-timestamp-for,android-test-runner-api-stubs-gen) \
    ))

.PHONY: check-android-test-runner-api
checkapi: check-android-test-runner-api

.PHONY: update-android-test-runner-api
update-api: update-android-test-runner-api

update-android-test-runner-api: $(ANDROID_TEST_RUNNER_OUTPUT_API_FILE) | $(ACP)
	@echo Copying current.txt
	$(hide) $(ACP) $(ANDROID_TEST_RUNNER_OUTPUT_API_FILE) $(ANDROID_TEST_RUNNER_API_FILE)
	@echo Copying removed.txt
	$(hide) $(ACP) $(ANDROID_TEST_RUNNER_OUTPUT_REMOVED_API_FILE) $(ANDROID_TEST_RUNNER_REMOVED_API_FILE)

# Build the android.test.mock library
# ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(android_test_mock_source_files)

LOCAL_JAVA_LIBRARIES := core-oj core-libart framework

LOCAL_MODULE:= android.test.mock

include $(BUILD_JAVA_LIBRARY)

# Generate the stub source files for android.test.mock.stubs
# ==========================================================
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(android_test_mock_source_files)

LOCAL_JAVA_LIBRARIES := core-oj core-libart framework
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_DROIDDOC_SOURCE_PATH := $(LOCAL_PATH)/src/android/test/mock

ANDROID_TEST_MOCK_OUTPUT_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.stubs_intermediates/api.txt
ANDROID_TEST_MOCK_OUTPUT_REMOVED_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.stubs_intermediates/removed.txt

ANDROID_TEST_MOCK_API_FILE := $(LOCAL_PATH)/api/android-test-mock-current.txt
ANDROID_TEST_MOCK_REMOVED_API_FILE := $(LOCAL_PATH)/api/android-test-mock-removed.txt

LOCAL_DROIDDOC_OPTIONS:= \
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

include $(BUILD_STATIC_JAVA_LIBRARY)

# Make sure to run droiddoc first to generate the stub source files.
$(full_classes_compiled_jar) : $(android_test_mock_gen_stamp)
$(full_classes_jack) : $(android_test_mock_gen_stamp)

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

# Build the android.test.mock.sdk library
# =======================================
include $(CLEAR_VARS)

LOCAL_MODULE := android.test.mock.sdk

LOCAL_STATIC_JAVA_LIBRARIES := android.test.mock.stubs

include $(BUILD_STATIC_JAVA_LIBRARY)

# additionally, build unit tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))
