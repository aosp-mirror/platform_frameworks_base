#
# Copyright (C) 2013 The Android Open Source Project
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

# the remotedisplay library
# ============================================================
include $(CLEAR_VARS)

LOCAL_MODULE:= com.android.media.remotedisplay
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, java)

include $(BUILD_JAVA_LIBRARY)


# ====  com.android.media.remotedisplay.xml lib def  ========================
include $(CLEAR_VARS)

LOCAL_MODULE := com.android.media.remotedisplay.xml
LOCAL_MODULE_TAGS := optional

LOCAL_MODULE_CLASS := ETC

# This will install the file in /system/etc/permissions
#
LOCAL_MODULE_PATH := $(TARGET_OUT_ETC)/permissions

LOCAL_SRC_FILES := $(LOCAL_MODULE)

include $(BUILD_PREBUILT)

include $(CLEAR_VARS)
LOCAL_MODULE := com.android.media.remotedisplay.stubs-gen
LOCAL_MODULE_CLASS := JAVA_LIBRARIES
LOCAL_SRC_FILES := $(call all-java-files-under,java)
LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/com.android.media.remotedisplay.stubs_intermediates/src
LOCAL_DROIDDOC_OPTIONS:= \
    -hide 111 -hide 113 -hide 125 -hide 126 -hide 127 -hide 128 \
    -stubpackages com.android.media.remotedisplay \
    -nodocs
LOCAL_UNINSTALLABLE_MODULE := true
include $(BUILD_DROIDDOC)
com_android_media_remotedisplay_gen_stamp := $(full_target)

include $(CLEAR_VARS)
LOCAL_MODULE := com.android.media.remotedisplay.stubs
LOCAL_SDK_VERSION := current
LOCAL_SOURCE_FILES_ALL_GENERATED := true
LOCAL_ADDITIONAL_DEPENDENCIES := $(com_android_media_remotedisplay_gen_stamp)
com_android_media_remotedisplay_gen_stamp :=
include $(BUILD_STATIC_JAVA_LIBRARY)
