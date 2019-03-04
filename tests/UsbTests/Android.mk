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

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_STATIC_JAVA_LIBRARIES := \
    frameworks-base-testutils \
    androidx.test.rules \
    mockito-target-inline-minus-junit4 \
    platform-test-annotations \
    services.core \
    services.net \
    services.usb \
    truth-prebuilt \

LOCAL_JNI_SHARED_LIBRARIES := \
    libdexmakerjvmtiagent \

LOCAL_CERTIFICATE := platform

LOCAL_PACKAGE_NAME := UsbTests
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
