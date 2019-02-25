#
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
#

LOCAL_PATH:= $(call my-dir)

# For unbundled build we'll use the prebuilt jar from prebuilts/sdk.
ifeq (,$(TARGET_BUILD_APPS)$(filter true,$(TARGET_BUILD_PDK)))

# Build the android.test.legacy library
# =====================================
# Built against the SDK so that it can be statically included in APKs
# without breaking link type checks.
#
include $(CLEAR_VARS)

LOCAL_MODULE := android.test.legacy

LOCAL_SDK_VERSION := current

LOCAL_JAVA_LIBRARIES := junit android.test.mock.stubs
LOCAL_STATIC_JAVA_LIBRARIES := \
    android.test.base-minus-junit \
    android.test.runner-minus-junit \

include $(BUILD_STATIC_JAVA_LIBRARY)

# Archive a copy of the classes.jar in SDK build.
$(call dist-for-goals,sdk win_sdk,$(full_classes_jar):android.test.legacy.jar)

endif  # not TARGET_BUILD_APPS not TARGET_BUILD_PDK=true
