# Copyright (C) 2012 The Android Open Source Project
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

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := 

LOCAL_PACKAGE_NAME := InputDevices
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

include $(BUILD_PACKAGE)

# Validate all key maps.
include $(CLEAR_VARS)

LOCAL_MODULE := validate_input_devices_keymaps
intermediates := $(call intermediates-dir-for,ETC,$(LOCAL_MODULE),,COMMON)
LOCAL_BUILT_MODULE := $(intermediates)/stamp

validatekeymaps := $(HOST_OUT_EXECUTABLES)/validatekeymaps$(HOST_EXECUTABLE_SUFFIX)
input_devices_keymaps := $(wildcard $(LOCAL_PATH)/res/raw/*.kcm)
$(LOCAL_BUILT_MODULE): PRIVATE_VALIDATEKEYMAPS := $(validatekeymaps)
$(LOCAL_BUILT_MODULE) : $(input_devices_keymaps) | $(validatekeymaps)
	$(hide) $(PRIVATE_VALIDATEKEYMAPS) $^
	$(hide) mkdir -p $(dir $@) && touch $@

# Run validatekeymaps unconditionally for platform build.
droidcore all_modules : $(LOCAL_BUILT_MODULE)

# Reset temp vars.
validatekeymaps :=
input_devices_keymaps :=
