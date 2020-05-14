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
