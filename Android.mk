#
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
#
LOCAL_PATH := $(call my-dir)

# Load framework-specific path mappings used later in the build.
include $(LOCAL_PATH)/pathmap.mk

# Build the master framework library.
# The framework contains too many method references (>64K) for poor old DEX.
# So we first build the framework as a monolithic static library then split it
# up into smaller pieces.
# ============================================================

# embedded builds use nothing in frameworks/base
ifneq ($(ANDROID_BUILD_EMBEDDED),true)

# Copy AIDL files to be preprocessed and included in the SDK,
# specified relative to the root of the build tree.
# ============================================================
include $(CLEAR_VARS)

# This is used by ide.mk as the list of source files that are
# always included.
INTERNAL_SDK_SOURCE_DIRS := $(addprefix $(LOCAL_PATH)/,$(dirs_to_document))

$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE))
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_API_FILE))
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_TEST_API_FILE))
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE):apistubs/android/public/api/android.txt)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_API_FILE):apistubs/android/system/api/android.txt)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_TEST_API_FILE):apistubs/android/test/api/android.txt)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_MODULE_LIB_API_FILE):apistubs/android/module-lib/api/android.txt)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_SERVER_API_FILE):apistubs/android/system-server/api/android.txt)

# sdk.atree needs to copy the whole dir: $(OUT_DOCS)/offline-sdk to the final zip.
# So keep offline-sdk-timestamp target here, and unzip offline-sdk-docs.zip to
# $(OUT_DOCS)/offline-sdk.
$(OUT_DOCS)/offline-sdk-timestamp: $(OUT_DOCS)/offline-sdk-docs-docs.zip
	$(hide) rm -rf $(OUT_DOCS)/offline-sdk
	$(hide) mkdir -p $(OUT_DOCS)/offline-sdk
	( unzip -qo $< -d $(OUT_DOCS)/offline-sdk && touch -f $@ ) || exit 1

.PHONY: docs offline-sdk-docs
docs offline-sdk-docs: $(OUT_DOCS)/offline-sdk-timestamp

SDK_METADATA_DIR :=$= $(call intermediates-dir-for,PACKAGING,framework-doc-stubs-metadata,,COMMON)
SDK_METADATA_FILES :=$= $(addprefix $(SDK_METADATA_DIR)/,\
    activity_actions.txt \
    broadcast_actions.txt \
    categories.txt \
    features.txt \
    service_actions.txt \
    widgets.txt)
SDK_METADATA :=$= $(firstword $(SDK_METADATA_FILES))
$(SDK_METADATA): .KATI_IMPLICIT_OUTPUTS := $(filter-out $(SDK_METADATA),$(SDK_METADATA_FILES))
$(SDK_METADATA): $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/framework-doc-stubs-metadata.zip
	rm -rf $(SDK_METADATA_DIR)
	mkdir -p $(SDK_METADATA_DIR)
	unzip -qo $< -d $(SDK_METADATA_DIR)

.PHONY: framework-doc-stubs
framework-doc-stubs: $(SDK_METADATA)

# Run this for checkbuild
checkbuild: doc-comment-check-docs

# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif

endif # ANDROID_BUILD_EMBEDDED
