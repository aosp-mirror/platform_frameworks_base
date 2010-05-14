# Copyright (C) 2010 The Android Open Source Project
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

LOCAL_MODULE := com.android.vcard
LOCAL_SRC_FILES := $(call all-java-files-under, java)

# Use google-common instead of android-common for using hidden code in telephony library.
# Use ext for using Quoted-Printable codec.
LOCAL_JAVA_LIBRARIES := google-common ext

include $(BUILD_STATIC_JAVA_LIBRARY)

# Build the test package.
include $(call all-makefiles-under, $(LOCAL_PATH))
