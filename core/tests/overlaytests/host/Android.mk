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

LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-java-files-under,src)
LOCAL_MODULE_TAGS := tests
LOCAL_MODULE := OverlayHostTests
LOCAL_JAVA_LIBRARIES := tradefed
LOCAL_COMPATIBILITY_SUITE := general-tests
LOCAL_TARGET_REQUIRED_MODULES := \
    OverlayHostTests_NonPlatformSignatureOverlay \
    OverlayHostTests_PlatformSignatureStaticOverlay \
    OverlayHostTests_PlatformSignatureOverlay \
    OverlayHostTests_UpdateOverlay \
    OverlayHostTests_FrameworkOverlayV1 \
    OverlayHostTests_FrameworkOverlayV2 \
    OverlayHostTests_AppOverlayV1 \
    OverlayHostTests_AppOverlayV2
include $(BUILD_HOST_JAVA_LIBRARY)

# Include to build test-apps.
include $(call all-makefiles-under,$(LOCAL_PATH))

