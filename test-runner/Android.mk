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

# For unbundled build we'll use the prebuilt jar from prebuilts/sdk.
ifeq (,$(TARGET_BUILD_APPS)$(filter true,$(TARGET_BUILD_PDK)))

# Generate the stub source files for android.test.runner.stubs
# ============================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := \
    core-oj \
    core-libart \
    framework \
    android.test.base \
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
    android.test.base.stubs \
    android.test.mock.stubs \

LOCAL_SOURCE_FILES_ALL_GENERATED := true
LOCAL_SDK_VERSION := current

# Make sure to run droiddoc first to generate the stub source files.
LOCAL_ADDITIONAL_DEPENDENCIES := $(android_test_runner_api_gen_stamp)
android_test_runner_api_gen_stamp :=

include $(BUILD_STATIC_JAVA_LIBRARY)

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

endif  # not TARGET_BUILD_APPS not TARGET_BUILD_PDK=true

# additionally, build unit tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))
