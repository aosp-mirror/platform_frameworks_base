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

# Build the android.test.runner library
# =====================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := core-oj core-libart framework legacy-test

LOCAL_MODULE:= android.test.runner

include $(BUILD_JAVA_LIBRARY)

# Build the android.test.mock library
# ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-java-files-under, src/android/test/mock)

LOCAL_JAVA_LIBRARIES := core-oj core-libart framework

LOCAL_MODULE:= android.test.mock

include $(BUILD_JAVA_LIBRARY)

# Generate the stub source files for android.test.mock.sdk
# ========================================================
include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-java-files-under, src/android/test/mock)

LOCAL_JAVA_LIBRARIES := core-oj core-libart framework
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_DROIDDOC_SOURCE_PATH := $(LOCAL_PATH)/src/android/test/mock

LOCAL_DROIDDOC_OPTIONS:= \
    -stubpackages android.test.mock \
    -stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android.test.mock.sdk_intermediates/src \
    -nodocs

LOCAL_UNINSTALLABLE_MODULE := true
LOCAL_MODULE := android-test-mock-stubs-gen

include $(BUILD_DROIDDOC)

# Remember the target that will trigger the code generation.
android_test_mock_gen_stamp := $(full_target)

# Build the android.test.mock.sdk library
# =======================================
include $(CLEAR_VARS)

LOCAL_MODULE := android.test.mock.sdk

LOCAL_SOURCE_FILES_ALL_GENERATED := true

include $(BUILD_STATIC_JAVA_LIBRARY)

# Make sure to run droiddoc first to generate the stub source files.
$(full_classes_compiled_jar) : $(android_test_mock_gen_stamp)
$(full_classes_jack) : $(android_test_mock_gen_stamp)

# Archive a copy of the classes.jar in SDK build.
$(call dist-for-goals,sdk win_sdk,$(full_classes_jar):android.test.mock.jar)

# additionally, build unit tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))
