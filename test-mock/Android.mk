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

# Archive a copy of the classes.jar in SDK build.
full_classes_jar := $(call intermediates-dir-for,JAVA_LIBRARIES,android.test.mock.stubs,,COMMON)/classes.jar
$(call dist-for-goals,sdk win_sdk,$(full_classes_jar):android.test.mock.stubs.jar)

# Check that the android.test.mock.stubs library has not changed
# ==============================================================
ANDROID_TEST_MOCK_API_FILE := $(LOCAL_PATH)/api/android-test-mock-current.txt
ANDROID_TEST_MOCK_REMOVED_API_FILE := $(LOCAL_PATH)/api/android-test-mock-removed.txt

# Check that the API we're building hasn't changed from the not-yet-released
# SDK version.
$(eval $(call check-api, \
    check-android-test-mock-api-current, \
    $(ANDROID_TEST_MOCK_API_FILE), \
    $(INTERNAL_PLATFORM_ANDROID_TEST_MOCK_API_FILE), \
    $(ANDROID_TEST_MOCK_REMOVED_API_FILE), \
    $(INTERNAL_PLATFORM_ANDROID_TEST_MOCK_REMOVED_API_FILE), \
    -error 2 -error 3 -error 4 -error 5 -error 6 \
    -error 7 -error 8 -error 9 -error 10 -error 11 -error 12 -error 13 -error 14 -error 15 \
    -error 16 -error 17 -error 18 -error 19 -error 20 -error 21 -error 23 -error 24 \
    -error 25 -error 26 -error 27, \
    cat $(LOCAL_PATH)/api/apicheck_msg_android_test_mock.txt, \
    check-android-test-mock-api, \
    $(OUT_DOCS)/android.test.mock.docs-stubs.srcjar \
    ))

.PHONY: check-android-test-mock-api
checkapi: check-android-test-mock-api

.PHONY: update-android-test-mock-api
update-api: update-android-test-mock-api

update-android-test-mock-api: $(INTERNAL_PLATFORM_ANDROID_TEST_MOCK_API_FILE) | $(ACP)
	@echo Copying current.txt
	$(hide) $(ACP) $(INTERNAL_PLATFORM_ANDROID_TEST_MOCK_API_FILE) $(ANDROID_TEST_MOCK_API_FILE)
	@echo Copying removed.txt
	$(hide) $(ACP) $(INTERNAL_PLATFORM_ANDROID_TEST_MOCK_REMOVED_API_FILE) $(ANDROID_TEST_MOCK_REMOVED_API_FILE)
