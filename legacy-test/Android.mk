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

# Build the legacy-test library
# =============================
# This contains the junit.framework and android.test classes that were in
# Android API level 25 excluding those from android.test.runner.
# Also contains the com.android.internal.util.Predicate[s] classes.
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_MODULE := legacy-test
LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core-oj core-libart framework

include $(BUILD_JAVA_LIBRARY)

# Build the repackaged-legacy-test library
# ========================================
# This contains repackaged versions of the classes from legacy-test.
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_MODULE := repackaged-legacy-test
LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core-oj core-libart framework
LOCAL_JARJAR_RULES := $(LOCAL_PATH)/jarjar-rules.txt

include $(BUILD_STATIC_JAVA_LIBRARY)

# Generate the stub source files for legacy.test.stubs
# ====================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := \
    core-oj \
    core-libart \
    framework \

LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_DROIDDOC_SOURCE_PATH := $(LOCAL_PATH)/src

LEGACY_TEST_OUTPUT_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/legacy.test.stubs_intermediates/api.txt
LEGACY_TEST_OUTPUT_REMOVED_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/legacy.test.stubs_intermediates/removed.txt

LEGACY_TEST_API_FILE := $(LOCAL_PATH)/api/legacy-test-current.txt
LEGACY_TEST_REMOVED_API_FILE := $(LOCAL_PATH)/api/legacy-test-removed.txt

LOCAL_DROIDDOC_OPTIONS:= \
    -stubpackages android.test:android.test.suitebuilder.annotation:com.android.internal.util:junit.framework \
    -stubsourceonly \
    -stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/legacy.test.stubs_intermediates/src \
    -nodocs \
    -api $(LEGACY_TEST_OUTPUT_API_FILE) \
    -removedApi $(LEGACY_TEST_OUTPUT_REMOVED_API_FILE) \

LOCAL_UNINSTALLABLE_MODULE := true
LOCAL_MODULE := legacy-test-api-stubs-gen

include $(BUILD_DROIDDOC)

# Remember the target that will trigger the code generation.
legacy_test_api_gen_stamp := $(full_target)

# Add some additional dependencies
$(LEGACY_TEST_OUTPUT_API_FILE): $(full_target)
$(LEGACY_TEST_OUTPUT_REMOVED_API_FILE): $(full_target)

# Build the legacy.test.stubs library
# ===================================
include $(CLEAR_VARS)

LOCAL_MODULE := legacy.test.stubs

LOCAL_SOURCE_FILES_ALL_GENERATED := true

include $(BUILD_STATIC_JAVA_LIBRARY)

# Make sure to run droiddoc first to generate the stub source files.
$(full_classes_compiled_jar) : $(legacy_test_api_gen_stamp)
$(full_classes_jack) : $(legacy_test_api_gen_stamp)

# Archive a copy of the classes.jar in SDK build.
$(call dist-for-goals,sdk win_sdk,$(full_classes_jar):legacy.test.stubs.jar)

# Check that the legacy.test.stubs library has not changed
# ========================================================

# Check that the API we're building hasn't changed from the not-yet-released
# SDK version.
$(eval $(call check-api, \
    check-legacy-test-api-current, \
    $(LEGACY_TEST_API_FILE), \
    $(LEGACY_TEST_OUTPUT_API_FILE), \
    $(LEGACY_TEST_REMOVED_API_FILE), \
    $(LEGACY_TEST_OUTPUT_REMOVED_API_FILE), \
    -error 2 -error 3 -error 4 -error 5 -error 6 \
    -error 7 -error 8 -error 9 -error 10 -error 11 -error 12 -error 13 -error 14 -error 15 \
    -error 16 -error 17 -error 18 -error 19 -error 20 -error 21 -error 23 -error 24 \
    -error 25 -error 26 -error 27, \
    cat $(LOCAL_PATH)/api/apicheck_msg_legacy_test.txt, \
    check-legacy-test-api, \
    $(call doc-timestamp-for,legacy-test-api-stubs-gen) \
    ))

.PHONY: check-legacy-test-api
checkapi: check-legacy-test-api

.PHONY: update-legacy-test-api
update-api: update-legacy-test-api

update-legacy-test-api: $(LEGACY_TEST_OUTPUT_API_FILE) | $(ACP)
	@echo Copying current.txt
	$(hide) $(ACP) $(LEGACY_TEST_OUTPUT_API_FILE) $(LEGACY_TEST_API_FILE)
	@echo Copying removed.txt
	$(hide) $(ACP) $(LEGACY_TEST_OUTPUT_REMOVED_API_FILE) $(LEGACY_TEST_REMOVED_API_FILE)

# Build the legacy-android-test library
# =====================================
# This contains the android.test classes that were in Android API level 25,
# including those from android.test.runner.
# Also contains the com.android.internal.util.Predicate[s] classes.
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src/android) \
    $(call all-java-files-under, ../test-runner/src/android) \
    $(call all-java-files-under, src/com)
LOCAL_MODULE := legacy-android-test
LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_JAVA_LIBRARIES := core-oj core-libart framework junit

include $(BUILD_STATIC_JAVA_LIBRARY)

ifeq ($(HOST_OS),linux)
# Build the legacy-performance-test-hostdex library
# =================================================
# This contains the android.test.PerformanceTestCase class only
include $(CLEAR_VARS)

LOCAL_SRC_FILES := src/android/test/PerformanceTestCase.java
LOCAL_MODULE := legacy-performance-test-hostdex

include $(BUILD_HOST_DALVIK_STATIC_JAVA_LIBRARY)
endif  # HOST_OS == linux
