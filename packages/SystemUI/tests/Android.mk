# Copyright (C) 2011 The Android Open Source Project
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

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true
LOCAL_MODULE_TAGS := tests

LOCAL_JACK_FLAGS := --multi-dex native
LOCAL_DX_FLAGS := --multi-dex

LOCAL_PACKAGE_NAME := SystemUITests
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_NOTICE_FILE  := $(LOCAL_PATH)/../NOTICE
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_STATIC_ANDROID_LIBRARIES := \
    SystemUI-tests

LOCAL_MULTILIB := both

LOCAL_JNI_SHARED_LIBRARIES := \
    libdexmakerjvmtiagent \
    libmultiplejvmtiagentsinterferenceagent \
    libstaticjvmtiagent

LOCAL_JAVA_LIBRARIES := \
    android.test.runner \
    telephony-common \
    android.test.base \

LOCAL_AAPT_FLAGS := --extra-packages com.android.systemui

# sign this with platform cert, so this test is allowed to inject key events into
# UI it doesn't own. This is necessary to allow screenshots to be taken
LOCAL_CERTIFICATE := platform

LOCAL_FULL_LIBS_MANIFEST_FILES := $(LOCAL_PATH)/AndroidManifest.xml
LOCAL_MANIFEST_FILE := AndroidManifest-base.xml

# Provide jack a list of classes to exclude from code coverage.
# This is needed because the SystemUITests compile SystemUI source directly, rather than using
# LOCAL_INSTRUMENTATION_FOR := SystemUI.
#
# We want to exclude the test classes from code coverage measurements, but they share the same
# package as the rest of SystemUI so they can't be easily filtered by package name.
#
# Generate a comma separated list of patterns based on the test source files under src/
# SystemUI classes are in ../src/ so they won't be excluded.
# Example:
#   Input files: src/com/android/systemui/Test.java src/com/android/systemui/AnotherTest.java
#   Generated exclude list: com.android.systemui.Test*,com.android.systemui.AnotherTest*

# Filter all src files under src/ to just java files
local_java_files := $(filter %.java,$(call all-java-files-under, src))
# Transform java file names into full class names.
# This only works if the class name matches the file name and the directory structure
# matches the package.
local_classes := $(subst /,.,$(patsubst src/%.java,%,$(local_java_files)))
local_comma := ,
local_empty :=
local_space := $(local_empty) $(local_empty)
# Convert class name list to jacoco exclude list
# This appends a * to all classes and replace the space separators with commas.
jacoco_exclude := $(subst $(space),$(comma),$(patsubst %,%*,$(local_classes)))

LOCAL_JACK_COVERAGE_INCLUDE_FILTER := com.android.systemui.*,com.android.keyguard.*
LOCAL_JACK_COVERAGE_EXCLUDE_FILTER := $(jacoco_exclude)

ifeq ($(EXCLUDE_SYSTEMUI_TESTS),)
    include $(BUILD_PACKAGE)
endif

# Reset variables
local_java_files :=
local_classes :=
local_comma :=
local_space :=
jacoco_exclude :=
