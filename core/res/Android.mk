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
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true
LOCAL_NO_STANDARD_LIBRARIES := true
LOCAL_PACKAGE_NAME := framework-res
LOCAL_CERTIFICATE := platform
LOCAL_MODULE_TAGS := optional

# Generate private symbols into the com.android.internal.R class
# so they are not accessible to 3rd party apps.
LOCAL_AAPT_FLAGS += --private-symbols com.android.internal

# Framework doesn't need versioning since it IS the platform.
LOCAL_AAPT_FLAGS += --no-auto-version

# Install this alongside the libraries.
LOCAL_MODULE_PATH := $(TARGET_OUT_JAVA_LIBRARIES)

# Create package-export.apk, which other packages can use to get
# PRODUCT-agnostic resource data like IDs and type definitions.
LOCAL_EXPORT_PACKAGE_RESOURCES := true

include $(BUILD_PACKAGE)
