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

LOCAL_PATH:= $(call my-dir)

# For unbundled build we'll use the prebuilt jar from prebuilts/sdk.
ifeq (,$(TARGET_BUILD_APPS)$(filter true,$(TARGET_BUILD_PDK)))

ANDROID_TEST_BASE_API_FILE := $(LOCAL_PATH)/api/android-test-base-current.txt
ANDROID_TEST_BASE_REMOVED_API_FILE := $(LOCAL_PATH)/api/android-test-base-removed.txt

full_classes_jar := $(call intermediates-dir-for,JAVA_LIBRARIES,android.test.base.stubs,,COMMON)/classes.jar
# Archive a copy of the classes.jar in SDK build.
$(call dist-for-goals,sdk win_sdk,$(full_classes_jar):android.test.base.stubs.jar)

# Check that the android.test.base.stubs library has not changed
# ==============================================================

# Check that the API we're building hasn't changed from the not-yet-released
# SDK version.
$(eval $(call check-api, \
    check-android-test-base-api-current, \
    $(ANDROID_TEST_BASE_API_FILE), \
    $(INTERNAL_PLATFORM_ANDROID_TEST_BASE_API_FILE), \
    $(ANDROID_TEST_BASE_REMOVED_API_FILE), \
    $(INTERNAL_PLATFORM_ANDROID_TEST_BASE_REMOVED_API_FILE), \
    -error 2 -error 3 -error 4 -error 5 -error 6 \
    -error 7 -error 8 -error 9 -error 10 -error 11 -error 12 -error 13 -error 14 -error 15 \
    -error 16 -error 17 -error 18 -error 19 -error 20 -error 21 -error 23 -error 24 \
    -error 25 -error 26 -error 27, \
    cat $(LOCAL_PATH)/api/apicheck_msg_android_test_base.txt, \
    check-android-test-base-api, \
    $(OUT_DOCS)/android-test-base-api-stubs-gen-docs-stubs.srcjar \
    ))

.PHONY: check-android-test-base-api
checkapi: check-android-test-base-api

.PHONY: update-android-test-base-api
update-api: update-android-test-base-api

update-android-test-base-api: $(INTERNAL_PLATFORM_ANDROID_TEST_BASE_API_FILE) | $(ACP)
	@echo Copying current.txt
	$(hide) $(ACP) $(INTERNAL_PLATFORM_ANDROID_TEST_BASE_API_FILE) $(ANDROID_TEST_BASE_API_FILE)
	@echo Copying removed.txt
	$(hide) $(ACP) $(INTERNAL_PLATFORM_ANDROID_TEST_BASE_REMOVED_API_FILE) $(ANDROID_TEST_BASE_REMOVED_API_FILE)

endif  # not TARGET_BUILD_APPS not TARGET_BUILD_PDK=true

ifeq ($(HOST_OS),linux)
# Build the legacy-performance-test-hostdex library
# =================================================
# This contains the android.test.PerformanceTestCase class only
include $(CLEAR_VARS)

LOCAL_SRC_FILES := src/android/test/PerformanceTestCase.java
LOCAL_MODULE := legacy-performance-test-hostdex

include $(BUILD_HOST_DALVIK_STATIC_JAVA_LIBRARY)
endif  # HOST_OS == linux
