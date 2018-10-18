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

aidl_parcelables :=
define stubs-to-aidl-parcelables
  gen := $(TARGET_OUT_COMMON_INTERMEDIATES)/$1.aidl
  aidl_parcelables += $$(gen)
  $$(gen): $(call java-lib-header-files,$1) $(HOST_OUT_EXECUTABLES)/sdkparcelables
	@echo Extract SDK parcelables: $$@
	rm -f $$@
	$(HOST_OUT_EXECUTABLES)/sdkparcelables $$< $$@
endef

$(foreach stubs,android_stubs_current android_test_stubs_current android_system_stubs_current,\
  $(eval $(call stubs-to-aidl-parcelables,$(stubs))))

gen := $(TARGET_OUT_COMMON_INTERMEDIATES)/framework.aidl
.KATI_RESTAT: $(gen)
$(gen): $(aidl_parcelables)
	@echo Combining SDK parcelables: $@
	rm -f $@.tmp
	cat $^ | sort -u > $@.tmp
	$(call commit-change-for-toc,$@)

# This is used by ide.mk as the list of source files that are
# always included.
INTERNAL_SDK_SOURCE_DIRS := $(addprefix $(LOCAL_PATH)/,$(dirs_to_document))

$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE))
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_API_FILE))
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_TEST_API_FILE))
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE):apistubs/android/public/api/android.txt)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_API_FILE):apistubs/android/system/api/android.txt)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_TEST_API_FILE):apistubs/android/test/api/android.txt)

# sdk.atree needs to copy the whole dir: $(OUT_DOCS)/offline-sdk to the final zip.
# So keep offline-sdk-timestamp target here, and unzip offline-sdk-docs.zip to
# $(OUT_DOCS)/offline-sdk.
$(OUT_DOCS)/offline-sdk-timestamp: $(OUT_DOCS)/offline-sdk-docs-docs.zip
	$(hide) rm -rf $(OUT_DOCS)/offline-sdk
	$(hide) mkdir -p $(OUT_DOCS)/offline-sdk
	( unzip -qo $< -d $(OUT_DOCS)/offline-sdk && touch -f $@ ) || exit 1

# ==== hiddenapi lists =======================================
.KATI_RESTAT: \
	$(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST) \
	$(INTERNAL_PLATFORM_HIDDENAPI_LIGHT_GREYLIST) \
	$(INTERNAL_PLATFORM_HIDDENAPI_DARK_GREYLIST) \
	$(INTERNAL_PLATFORM_HIDDENAPI_BLACKLIST)
$(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST): \
    .KATI_IMPLICIT_OUTPUTS := \
        $(INTERNAL_PLATFORM_HIDDENAPI_LIGHT_GREYLIST) \
        $(INTERNAL_PLATFORM_HIDDENAPI_DARK_GREYLIST) \
        $(INTERNAL_PLATFORM_HIDDENAPI_BLACKLIST)
$(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST): \
    frameworks/base/tools/hiddenapi/generate_hiddenapi_lists.py \
    frameworks/base/config/hiddenapi-light-greylist.txt \
    frameworks/base/config/hiddenapi-vendor-list.txt \
    frameworks/base/config/hiddenapi-max-sdk-p-blacklist.txt \
    frameworks/base/config/hiddenapi-force-blacklist.txt \
    $(INTERNAL_PLATFORM_HIDDENAPI_PUBLIC_LIST) \
    $(INTERNAL_PLATFORM_HIDDENAPI_PRIVATE_LIST) \
    $(INTERNAL_PLATFORM_REMOVED_DEX_API_FILE)
	frameworks/base/tools/hiddenapi/generate_hiddenapi_lists.py \
	    --input-public $(INTERNAL_PLATFORM_HIDDENAPI_PUBLIC_LIST) \
	    --input-private $(INTERNAL_PLATFORM_HIDDENAPI_PRIVATE_LIST) \
	    --input-whitelists $(PRIVATE_WHITELIST_INPUTS) \
	    --input-greylists \
	        frameworks/base/config/hiddenapi-light-greylist.txt \
	        frameworks/base/config/hiddenapi-vendor-list.txt \
	        frameworks/base/config/hiddenapi-max-sdk-p-blacklist.txt \
	        <(comm -12 <(sort $(INTERNAL_PLATFORM_REMOVED_DEX_API_FILE)) \
	                   $(INTERNAL_PLATFORM_HIDDENAPI_PRIVATE_LIST)) \
	        $(PRIVATE_GREYLIST_INPUTS) \
	    --input-blacklists frameworks/base/config/hiddenapi-force-blacklist.txt \
	    --output-whitelist $(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST).tmp \
	    --output-light-greylist $(INTERNAL_PLATFORM_HIDDENAPI_LIGHT_GREYLIST).tmp \
	    --output-dark-greylist $(INTERNAL_PLATFORM_HIDDENAPI_DARK_GREYLIST).tmp \
	    --output-blacklist $(INTERNAL_PLATFORM_HIDDENAPI_BLACKLIST).tmp
	$(call commit-change-for-toc,$(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST))
	$(call commit-change-for-toc,$(INTERNAL_PLATFORM_HIDDENAPI_LIGHT_GREYLIST))
	$(call commit-change-for-toc,$(INTERNAL_PLATFORM_HIDDENAPI_DARK_GREYLIST))
	$(call commit-change-for-toc,$(INTERNAL_PLATFORM_HIDDENAPI_BLACKLIST))

$(INTERNAL_PLATFORM_HIDDENAPI_GREYLIST_METADATA): \
    frameworks/base/tools/hiddenapi/merge_csv.py \
    $(PRIVATE_METADATA_INPUTS)
	frameworks/base/tools/hiddenapi/merge_csv.py $(PRIVATE_METADATA_INPUTS) > $@

$(call dist-for-goals,droidcore,$(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST))
$(call dist-for-goals,droidcore,$(INTERNAL_PLATFORM_HIDDENAPI_LIGHT_GREYLIST))
$(call dist-for-goals,droidcore,$(INTERNAL_PLATFORM_HIDDENAPI_DARK_GREYLIST))
$(call dist-for-goals,droidcore,$(INTERNAL_PLATFORM_HIDDENAPI_BLACKLIST))
$(call dist-for-goals,droidcore,$(INTERNAL_PLATFORM_HIDDENAPI_GREYLIST_METADATA))

# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif

endif # ANDROID_BUILD_EMBEDDED
