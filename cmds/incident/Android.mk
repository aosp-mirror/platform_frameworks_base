# Copyright (C) 2016 The Android Open Source Project
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

LOCAL_SRC_FILES := \
        main.cpp

LOCAL_MODULE := incident

LOCAL_SHARED_LIBRARIES := \
        libbase \
        libbinder \
        libcutils \
        liblog \
        libutils \
        libincident

LOCAL_CFLAGS += \
        -Wall -Werror -Wno-missing-field-initializers -Wno-unused-variable -Wunused-parameter

LOCAL_MODULE_CLASS := EXECUTABLES
gen_src_dir := $(local-generated-sources-dir)

gen := $(gen_src_dir)/incident_sections.cpp
$(gen): $(HOST_OUT_EXECUTABLES)/incident-section-gen
$(gen): PRIVATE_CUSTOM_TOOL = \
    $(HOST_OUT_EXECUTABLES)/incident-section-gen incident > $@
$(gen): $(HOST_OUT_EXECUTABLES)/incident-section-gen
	$(transform-generated-source)
LOCAL_GENERATED_SOURCES += $(gen)

gen_src_dir:=
gen:=

include $(BUILD_EXECUTABLE)
