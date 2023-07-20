# Copyright (C) 2019 The Android Open Source Project
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
include $(CLEAR_VARS)

LOCAL_MODULE := frameworks-base-overlays
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/../../NOTICE
LOCAL_REQUIRED_MODULES := \
	DisplayCutoutEmulationCornerOverlay \
	DisplayCutoutEmulationDoubleOverlay \
	DisplayCutoutEmulationHoleOverlay \
	DisplayCutoutEmulationTallOverlay \
	DisplayCutoutEmulationWaterfallOverlay \
	FontNotoSerifSourceOverlay \
	NavigationBarMode3ButtonOverlay \
	NavigationBarModeGesturalOverlay \
	NavigationBarModeGesturalOverlayNarrowBack \
	NavigationBarModeGesturalOverlayWideBack \
	NavigationBarModeGesturalOverlayExtraWideBack \
        NavigationBarModeGesturalOverlayFS \
	NotchBarKillerLeftrOverlay \
	NotchBarKillerOverlay \
    qs_portrait_2x2 \
    qs_portrait_2x3 \
    qs_portrait_2x4 \
    qs_portrait_2x5 \
    qs_portrait_2x6 \
    qs_portrait_3x2 \
    qs_portrait_3x3 \
    qs_portrait_3x4 \
    qs_portrait_3x5 \
    qs_portrait_3x6 \
    qs_portrait_4x2 \
    qs_portrait_4x3 \
    qs_portrait_4x4 \
    qs_portrait_4x5 \
    qs_portrait_4x6 \
    qs_portrait_5x2 \
    qs_portrait_5x3 \
    qs_portrait_5x4 \
    qs_portrait_5x5 \
    qs_portrait_5x6 \
    qs_portrait_6x2 \
    qs_portrait_6x3 \
    qs_portrait_6x4 \
    qs_portrait_6x5 \
    qs_portrait_6x6 \
    qqs_portrait_1x2 \
    qqs_portrait_1x3 \
    qqs_portrait_1x4 \
    qqs_portrait_1x5 \
    qqs_portrait_1x6 \
    qqs_portrait_2x2 \
    qqs_portrait_2x3 \
    qqs_portrait_2x4 \
    qqs_portrait_2x5 \
    qqs_portrait_2x6 \
    qqs_portrait_3x2 \
    qqs_portrait_3x3 \
    qqs_portrait_3x4 \
    qqs_portrait_3x5 \
    qqs_portrait_3x6 \
    qqs_portrait_4x2 \
    qqs_portrait_4x3 \
    qqs_portrait_4x4 \
    qqs_portrait_4x5 \
    qqs_portrait_4x6 \
    qqs_portrait_5x2 \
    qqs_portrait_5x3 \
    qqs_portrait_5x4 \
    qqs_portrait_5x5 \
    qqs_portrait_5x6 \
    preinstalled-packages-platform-overlays.xml

include $(BUILD_PHONY_PACKAGE)
include $(CLEAR_VARS)

LOCAL_MODULE := frameworks-base-overlays-debug
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_NOTICE_FILE := $(LOCAL_PATH)/../../NOTICE

include $(BUILD_PHONY_PACKAGE)
include $(call first-makefiles-under,$(LOCAL_PATH))
