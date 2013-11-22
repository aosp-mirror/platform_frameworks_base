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

# This makefile performs build time validation of framework keymap files.

LOCAL_PATH := $(call my-dir)

include $(LOCAL_PATH)/common.mk

# Validate all key maps.
include $(CLEAR_VARS)

LOCAL_MODULE := validate_framework_keymaps
intermediates := $(call intermediates-dir-for,ETC,$(LOCAL_MODULE),,COMMON)
LOCAL_BUILT_MODULE := $(intermediates)/stamp

validatekeymaps := $(HOST_OUT_EXECUTABLES)/validatekeymaps$(HOST_EXECUTABLE_SUFFIX)
$(LOCAL_BUILT_MODULE): PRIVATE_VALIDATEKEYMAPS := $(validatekeymaps)
$(LOCAL_BUILT_MODULE) : $(framework_keylayouts) $(framework_keycharmaps) $(framework_keyconfigs) | $(validatekeymaps)
	$(hide) $(PRIVATE_VALIDATEKEYMAPS) $^
	$(hide) mkdir -p $(dir $@) && touch $@

# Run validatekeymaps uncondionally for platform build.
droidcore all_modules : $(LOCAL_BUILT_MODULE)

# Reset temp vars.
validatekeymaps :=
framework_keylayouts :=
framework_keycharmaps :=
framework_keyconfigs :=
