# Copyright (C) 2014 The Android Open Source Project
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

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_SDK_VERSION := 9

LOCAL_PACKAGE_NAME := MultiDexLegacyVersionedTestApp_v2

LOCAL_STATIC_JAVA_LIBRARIES := android-support-multidex

LOCAL_DEX_PREOPT := false

mainDexList:= \
	$(call intermediates-dir-for,APPS,$(LOCAL_PACKAGE_NAME),$(LOCAL_IS_HOST_MODULE),common)/maindex.list

LOCAL_DX_FLAGS := --multi-dex --main-dex-list=$(mainDexList) --minimal-main-dex
LOCAL_JACK_FLAGS := -D jack.dex.output.policy=minimal-multidex -D jack.preprocessor=true\
    -D jack.preprocessor.file=$(LOCAL_PATH)/test.jpp -D jack.dex.output.multidex.legacy=true

#################################
include $(BUILD_SYSTEM)/configure_local_jack.mk
#################################

ifdef LOCAL_JACK_ENABLED
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/test.jpp
endif

include $(BUILD_PACKAGE)

ifndef LOCAL_JACK_ENABLED
$(mainDexList): $(full_classes_proguard_jar) | $(MAINDEXCLASSES)
	$(hide) mkdir -p $(dir $@)
	$(MAINDEXCLASSES) $< 1>$@
	echo "com/android/framework/multidexlegacyversionedtestapp/MultiDexUpdateTest.class" >> $@

$(built_dex_intermediate): $(mainDexList)
endif
