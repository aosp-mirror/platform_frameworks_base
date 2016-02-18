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

# Only compile source java files in this lib.
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_JAVA_RESOURCE_DIRS := res

LOCAL_MODULE := layoutlib-tests
LOCAL_MODULE_TAGS := optional

LOCAL_JAVA_LIBRARIES := layoutlib \
			kxml2-2.3.0 \
			layoutlib_api-prebuilt \
			tools-common-prebuilt \
			sdk-common \
			junit

include $(BUILD_HOST_JAVA_LIBRARY)

# Copy the jar to DIST_DIR for sdk builds
$(call dist-for-goals, sdk win_sdk, $(LOCAL_INSTALLED_MODULE))

# Build all sub-directories
include $(call all-makefiles-under,$(LOCAL_PATH))
