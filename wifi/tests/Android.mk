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

LOCAL_PATH:= $(call my-dir)

# Make test APK
# ============================================================
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

# This list is generated from the java source files in this module
# The list is a comma separated list of class names with * matching zero or more characters.
# Example:
#   Input files: src/com/android/server/wifi/Test.java src/com/android/server/wifi/AnotherTest.java
#   Generated exclude list: com.android.server.wifi.Test*,com.android.server.wifi.AnotherTest*

# Filter all src files to just java files
local_java_files := $(filter %.java,$(LOCAL_SRC_FILES))
# Transform java file names into full class names.
# This only works if the class name matches the file name and the directory structure
# matches the package.
local_classes := $(subst /,.,$(patsubst src/%.java,%,$(local_java_files)))
# Convert class name list to jacoco exclude list
# This appends a * to all classes and replace the space separators with commas.
# These patterns will match all classes in this module and their inner classes.
jacoco_exclude := $(subst $(space),$(comma),$(patsubst %,%*,$(local_classes)))

jacoco_include := android.net.wifi.*

LOCAL_JACK_COVERAGE_INCLUDE_FILTER := $(jacoco_include)
LOCAL_JACK_COVERAGE_EXCLUDE_FILTER := $(jacoco_exclude)

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.test.rules \
    core-test-rules \
    guava \
    mockito-target-minus-junit4 \
    frameworks-base-testutils \
    truth-prebuilt \

LOCAL_JAVA_LIBRARIES := \
    android.test.runner \
    android.test.base \

LOCAL_PACKAGE_NAME := FrameworksWifiApiTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
