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
# because MINIMAL_FONT_FOOTPRINT is only available in Android.mks.

LOCAL_PATH := $(call my-dir)

##########################################
# create symlink for given font
# $(1): new font $(2): link target
# should be used with eval: $(eval $(call ...))
define create-font-symlink
$(PRODUCT_OUT)/system/fonts/$(1) : $(PRODUCT_OUT)/system/fonts/$(2)
	@echo "Symlink: $$@ -> $$<"
	@mkdir -p $$(dir $$@)
	@rm -rf $$@
	$(hide) ln -sf $$(notdir $$<) $$@
# this magic makes LOCAL_REQUIRED_MODULES work
ALL_MODULES.$(1).INSTALLED := \
    $(ALL_MODULES.$(1).INSTALLED) $(PRODUCT_OUT)/system/fonts/$(1)
endef

##########################################
# The following fonts are just symlinks, for backward compatibility.
##########################################
$(eval $(call create-font-symlink,DroidSans.ttf,Roboto-Regular.ttf))
$(eval $(call create-font-symlink,DroidSans-Bold.ttf,Roboto-Bold.ttf))
$(eval $(call create-font-symlink,DroidSerif-Regular.ttf,NotoSerif-Regular.ttf))
$(eval $(call create-font-symlink,DroidSerif-Bold.ttf,NotoSerif-Bold.ttf))
$(eval $(call create-font-symlink,DroidSerif-Italic.ttf,NotoSerif-Italic.ttf))
$(eval $(call create-font-symlink,DroidSerif-BoldItalic.ttf,NotoSerif-BoldItalic.ttf))

extra_font_files := \
    DroidSans.ttf \
    DroidSans-Bold.ttf

################################
# Use DroidSansMono to hang extra_font_files on
include $(CLEAR_VARS)
LOCAL_MODULE := DroidSansMono.ttf
LOCAL_SRC_FILES := $(LOCAL_MODULE)
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(TARGET_OUT)/fonts
LOCAL_REQUIRED_MODULES := $(extra_font_files)
include $(BUILD_PREBUILT)
extra_font_files :=

################################
# Build the rest of font files as prebuilt.

# $(1): The source file name in LOCAL_PATH.
#       It also serves as the module name and the dest file name.
define build-one-font-module
$(eval include $(CLEAR_VARS))\
$(eval LOCAL_MODULE := $(1))\
$(eval LOCAL_SRC_FILES := $(1))\
$(eval LOCAL_MODULE_CLASS := ETC)\
$(eval LOCAL_MODULE_TAGS := optional)\
$(eval LOCAL_MODULE_PATH := $(TARGET_OUT)/fonts)\
$(eval include $(BUILD_PREBUILT))
endef

font_src_files := \
    AndroidClock.ttf

$(foreach f, $(font_src_files), $(call build-one-font-module, $(f)))

build-one-font-module :=
font_src_files :=

################################
# Copies the font configuration file into system/etc for the product as fonts.xml.
# Additional fonts should be installed to /product/fonts/ alongside a corresponding
# fonts_customiztion.xml in /product/etc/
include $(CLEAR_VARS)

LOCAL_MODULE := fonts.xml
LOCAL_MODULE_CLASS := ETC
LOCAL_PREBUILT_MODULE_FILE := frameworks/base/data/fonts/fonts.xml

include $(BUILD_PREBUILT)

# Run sanity tests on fonts on checkbuild
checkbuild: fontchain_lint

FONTCHAIN_LINTER := $(HOST_OUT_EXECUTABLES)/fontchain_linter
ifeq ($(MINIMAL_FONT_FOOTPRINT),true)
CHECK_EMOJI := false
else
CHECK_EMOJI := true
endif

fontchain_lint_timestamp := $(call intermediates-dir-for,PACKAGING,fontchain_lint)/stamp

.PHONY: fontchain_lint
fontchain_lint: $(fontchain_lint_timestamp)

fontchain_lint_deps := \
    external/unicode/DerivedAge.txt \
    external/unicode/emoji-data.txt \
    external/unicode/emoji-sequences.txt \
    external/unicode/emoji-variation-sequences.txt \
    external/unicode/emoji-zwj-sequences.txt \
    external/unicode/additions/emoji-data.txt \
    external/unicode/additions/emoji-sequences.txt \
    external/unicode/additions/emoji-zwj-sequences.txt \

$(fontchain_lint_timestamp): $(FONTCHAIN_LINTER) $(TARGET_OUT)/etc/fonts.xml $(PRODUCT_OUT)/system.img $(fontchain_lint_deps)
	@echo Running fontchain lint
	$(FONTCHAIN_LINTER) $(TARGET_OUT) $(CHECK_EMOJI) external/unicode
	touch $@
