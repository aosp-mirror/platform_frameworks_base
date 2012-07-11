# Copyright 2008, The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

LOCAL_JAVA_LIBRARIES := android.test.runner telephony-common

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SRC_FILES += \
        src/com/android/smspush/unitTests/IDataVerify.aidl


# Notice that we don't have to include the src files of Email because, by
# running the tests using an instrumentation targeting Eamil, we
# automatically get all of its classes loaded into our environment.

LOCAL_PACKAGE_NAME := WAPPushManagerTests

LOCAL_INSTRUMENTATION_FOR := WAPPushManager

include $(BUILD_PACKAGE)

