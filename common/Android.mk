# Copyright (C) 2009 The Android Open Source Project
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

# Note: the source code is in java/, not src/, because this code is also part of
# the framework library, and build/core/pathmap.mk expects a java/ subdirectory.

include $(CLEAR_VARS)
LOCAL_MODULE := android-common
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := \
     $(call all-java-files-under, java) \
     $(call all-logtags-files-under, java)
include $(BUILD_STATIC_JAVA_LIBRARY)

# Include this library in the build server's output directory
$(call dist-for-goals, droidcore, $(LOCAL_BUILT_MODULE):android-common.jar)

# Build the test package
include $(call all-makefiles-under, $(LOCAL_PATH))
