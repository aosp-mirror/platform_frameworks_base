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

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := flickerlib
LOCAL_MODULE_TAGS := tests optional
# sign this with platform cert, so this test is allowed to call private platform apis
LOCAL_CERTIFICATE := platform
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_STATIC_JAVA_LIBRARIES := \
   androidx.test.janktesthelper \
   cts-amwm-util \
   platformprotosnano \
   layersprotosnano \
   truth-prebuilt \
   sysui-helper \
   launcher-helper-lib \

include $(BUILD_STATIC_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := flickerautomationhelperlib
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := src/com/android/server/wm/flicker/AutomationUtils.java \
    src/com/android/server/wm/flicker/WindowUtils.java
LOCAL_STATIC_JAVA_LIBRARIES := sysui-helper \
    launcher-helper-lib \
    compatibility-device-util-axt

include $(BUILD_STATIC_JAVA_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
