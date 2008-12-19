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

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

copy_from :=                \
    DroidSans.ttf           \
    DroidSans-Bold.ttf      \
    DroidSerif-Regular.ttf  \
    DroidSerif-Bold.ttf     \
    DroidSerif-Italic.ttf   \
    DroidSerif-BoldItalic.ttf   \
    DroidSansMono.ttf

ifneq ($(NO_FALLBACK_FONT),true)
    copy_from += DroidSansFallback.ttf
endif

copy_to := $(addprefix $(TARGET_OUT)/fonts/,$(copy_from))

$(copy_to) : PRIVATE_MODULE := fonts
$(copy_to) : $(TARGET_OUT)/fonts/% : $(LOCAL_PATH)/% | $(ACP)
	$(transform-prebuilt-to-target)

ALL_PREBUILT += $(copy_to)

