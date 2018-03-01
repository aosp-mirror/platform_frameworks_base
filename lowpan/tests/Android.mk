# Copyright (C) 2017 The Android Open Source Project
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
#   Input files: src/com/android/server/lowpan/Test.java src/com/android/server/lowpan/AnotherTest.java
#   Generated exclude list: com.android.server.lowpan.Test*,com.android.server.lowpan.AnotherTest*

# Filter all src files to just java files
local_java_files := $(filter %.java,$(LOCAL_SRC_FILES))
# Transform java file names into full class names.
# This only works if the class name matches the file name and the directory structure
# matches the package.
local_classes := $(subst /,.,$(patsubst src/%.java,%,$(local_java_files)))
# Utility variables to allow replacing a space with a comma
comma:= ,
empty:=
space:= $(empty) $(empty)
# Convert class name list to jacoco exclude list
# This appends a * to all classes and replace the space separators with commas.
# These patterns will match all classes in this module and their inner classes.
jacoco_exclude := $(subst $(space),$(comma),$(patsubst %,%*,$(local_classes)))

jacoco_include := android.net.lowpan.*

LOCAL_JACK_COVERAGE_INCLUDE_FILTER := $(jacoco_include)
LOCAL_JACK_COVERAGE_EXCLUDE_FILTER := $(jacoco_exclude)

LOCAL_STATIC_JAVA_LIBRARIES := \
	android-support-test \
	guava \
	mockito-target-minus-junit4 \
	frameworks-base-testutils \

LOCAL_JAVA_LIBRARIES := \
	android.test.runner \
	android.test.base \

LOCAL_PACKAGE_NAME := FrameworksLowpanApiTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_CERTIFICATE := platform
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk
include $(BUILD_PACKAGE)
