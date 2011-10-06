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

# We have to use BUILD_PREBUILT instead of PRODUCT_COPY_FIES,
# because SMALLER_FONT_FOOTPRINT is only available in Android.mks.

LOCAL_PATH := $(call my-dir)

##########################################
# We may only afford small font footprint.
##########################################
# Use only symlinks.
# Symlink: DroidSans.ttf -> Roboto-Regular.ttf
LOCAL_MODULE := DroidSans.ttf
font_symlink_src := $(PRODUCT_OUT)/system/fonts/Roboto-Regular.ttf
font_symlink := $(dir $(font_symlink_src))$(LOCAL_MODULE)
$(font_symlink) : $(font_symlink_src)
	@echo "Symlink: $@ -> $<"
	@mkdir -p $(dir $@)
	@rm -rf $@
	$(hide) ln -sf $(notdir $<) $@

# this magic makes LOCAL_REQUIRED_MODULES work
ALL_MODULES.$(LOCAL_MODULE).INSTALLED := \
    $(ALL_MODULES.$(LOCAL_MODULE).INSTALLED) $(font_symlink)

################################
# Symlink: DroidSans-Bold.ttf -> Roboto-Bold.ttf
LOCAL_MODULE := DroidSans-Bold.ttf
font_symlink_src := $(PRODUCT_OUT)/system/fonts/Roboto-Bold.ttf
font_symlink := $(dir $(font_symlink_src))$(LOCAL_MODULE)
$(font_symlink) : $(font_symlink_src)
	@echo "Symlink: $@ -> $<"
	@mkdir -p $(dir $@)
	@rm -rf $@
	$(hide) ln -sf $(notdir $<) $@

# this magic makes LOCAL_REQUIRED_MODULES work
ALL_MODULES.$(LOCAL_MODULE).INSTALLED := \
    $(ALL_MODULES.$(LOCAL_MODULE).INSTALLED) $(font_symlink)

################################
include $(CLEAR_VARS)
LOCAL_MODULE := DroidSansEthiopic-Regular.ttf
LOCAL_SRC_FILES := $(LOCAL_MODULE)
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT)/fonts
include $(BUILD_PREBUILT)

################################
ifeq ($(SMALLER_FONT_FOOTPRINT),true)
droidsans_fallback_src := DroidSansFallback.ttf
extra_droidsans_fonts := DroidSans.ttf DroidSans-Bold.ttf
else
droidsans_fallback_src := DroidSansFallbackFull.ttf
extra_droidsans_fonts := DroidSans.ttf DroidSans-Bold.ttf DroidSansEthiopic-Regular.ttf
endif  # SMALLER_FONT_FOOTPRINT

include $(CLEAR_VARS)
LOCAL_MODULE := DroidSansFallback.ttf
LOCAL_SRC_FILES := $(droidsans_fallback_src)
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT)/fonts
LOCAL_REQUIRED_MODULES := $(extra_droidsans_fonts)
include $(BUILD_PREBUILT)

font_symlink_src :=
font_symlink :=
droidsans_fallback_src :=
extra_droidsans_fonts :=
