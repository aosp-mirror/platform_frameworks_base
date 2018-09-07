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

# the documentation
# ============================================================

# TODO: deal with com/google/android/googleapps
packages_to_document := \
  android \
  javax/microedition/khronos \
  org/apache/http/conn \
  org/apache/http/params \

# include definition of libcore_to_document
include libcore/Docs.mk

non_base_dirs := \
  ../opt/telephony/src/java/android/telephony \
  ../opt/telephony/src/java/android/telephony/gsm \
  ../opt/net/voip/src/java/android/net/rtp \
  ../opt/net/voip/src/java/android/net/sip \

# Find all files in specific directories (relative to frameworks/base)
# to document and check apis
files_to_check_apis := \
  $(call find-other-java-files, \
    $(non_base_dirs) \
  )

# Find all files in specific packages that were used to compile
# framework.jar to document and check apis
files_to_check_apis += \
  $(addprefix ../../,\
    $(filter \
      $(foreach dir,$(FRAMEWORKS_BASE_JAVA_SRC_DIRS),\
        $(foreach package,$(packages_to_document),\
          $(dir)/$(package)/%.java)),\
      $(SOONG_FRAMEWORK_SRCS)))

# Find all generated files that were used to compile framework.jar
files_to_check_apis_generated := \
  $(filter $(OUT_DIR)/%,\
    $(SOONG_FRAMEWORK_SRCS))

# These are relative to frameworks/base
# FRAMEWORKS_BASE_SUBDIRS comes from build/core/pathmap.mk
files_to_document := \
  $(files_to_check_apis) \
  $(call find-other-java-files,\
    test-base/src \
    test-mock/src \
    test-runner/src)

# These are relative to frameworks/base
html_dirs := \
	$(FRAMEWORKS_BASE_SUBDIRS) \
	$(non_base_dirs) \

# Common sources for doc check and api check
common_src_files := \
	$(call find-other-html-files, $(html_dirs)) \
	$(addprefix ../../, $(libcore_to_document)) \

# These are relative to frameworks/base
framework_docs_LOCAL_SRC_FILES := \
  $(files_to_document) \
  $(common_src_files) \

# These are relative to frameworks/base
framework_docs_LOCAL_API_CHECK_SRC_FILES := \
  $(files_to_check_apis) \
  $(common_src_files) \

# This is used by ide.mk as the list of source files that are
# always included.
INTERNAL_SDK_SOURCE_DIRS := $(addprefix $(LOCAL_PATH)/,$(dirs_to_document))

framework_docs_LOCAL_DROIDDOC_SOURCE_PATH := \
	$(FRAMEWORKS_BASE_JAVA_SRC_DIRS)

framework_docs_LOCAL_SRCJARS := $(SOONG_FRAMEWORK_SRCJARS)

framework_docs_LOCAL_GENERATED_SOURCES := \
  $(libcore_to_document_generated) \
  $(files_to_check_apis_generated) \

framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES := \
	core-oj \
	core-libart \
	conscrypt \
	bouncycastle \
	okhttp \
	ext \
	framework \
	voip-common \

# Platform docs can refer to Support Library APIs, but we don't actually build
# them as part of the docs target, so we need to include them on the classpath.
framework_docs_LOCAL_JAVA_LIBRARIES := \
	$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES) \
	$(FRAMEWORKS_SUPPORT_JAVA_LIBRARIES)

framework_docs_LOCAL_MODULE_CLASS := JAVA_LIBRARIES
framework_docs_LOCAL_DROIDDOC_HTML_DIR := docs/html
# The since flag (-since N.xml API_LEVEL) is used to add API Level information
# to the reference documentation. Must be in order of oldest to newest.
#
# Conscrypt (com.android.org.conscrypt) is an implementation detail and should
# not be referenced in the documentation.
framework_docs_LOCAL_DROIDDOC_OPTIONS := \
    -android \
    -knowntags ./frameworks/base/docs/knowntags.txt \
    -knowntags ./libcore/known_oj_tags.txt \
    -manifest ./frameworks/base/core/res/AndroidManifest.xml \
    -hidePackage com.android.internal \
    -hidePackage com.android.internal.util \
    -hidePackage com.android.okhttp \
    -hidePackage com.android.org.conscrypt \
    -hidePackage com.android.server

# Convert an sdk level to a "since" argument.
since-arg = -since $(wildcard $(HISTORICAL_SDK_VERSIONS_ROOT)/$(1)/public/api/android.$(2)) $(1)

finalized_xml_sdks := $(call numerically_sort,\
    $(patsubst $(HISTORICAL_SDK_VERSIONS_ROOT)/%/public/api/android.xml,%,\
    $(wildcard $(HISTORICAL_SDK_VERSIONS_ROOT)/*/public/api/android.xml)))
finalized_txt_sdks := $(call numerically_sort,\
     $(patsubst $(HISTORICAL_SDK_VERSIONS_ROOT)/%/public/api/android.txt,%,\
    $(wildcard $(HISTORICAL_SDK_VERSIONS_ROOT)/*/public/api/android.txt)))

framework_docs_LOCAL_DROIDDOC_OPTIONS += $(foreach sdk,$(finalized_xml_sdks),$(call since-arg,$(sdk),xml))
framework_docs_LOCAL_DROIDDOC_OPTIONS += $(foreach sdk,$(finalized_txt_sdks),$(call since-arg,$(sdk),txt))
ifneq ($(PLATFORM_VERSION_CODENAME),REL)
  framework_docs_LOCAL_DROIDDOC_OPTIONS += \
      -since ./frameworks/base/api/current.txt $(PLATFORM_VERSION_CODENAME)
endif
framework_docs_LOCAL_DROIDDOC_OPTIONS += \
    -werror -lerror -hide 111 -hide 113 -hide 125 -hide 126 -hide 127 -hide 128 \
    -overview $(LOCAL_PATH)/core/java/overview.html

framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR:= \
	$(call intermediates-dir-for,JAVA_LIBRARIES,framework,,COMMON)

framework_docs_LOCAL_ADDITIONAL_JAVA_DIR:= \
	$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)

framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES := \
    frameworks/base/docs/knowntags.txt \
    $(libcore_to_document_generated)

samples_dir := development/samples/browseable

# Whitelist of valid groups, used for default TOC grouping. Each sample must
# belong to one (and only one) group. Assign samples to groups by setting
# a sample.group var to one of these groups in the sample's _index.jd.
sample_groups := -samplegroup Admin \
                 -samplegroup Background \
                 -samplegroup Connectivity \
                 -samplegroup Content \
                 -samplegroup Input \
                 -samplegroup Media \
                 -samplegroup Notification \
                 -samplegroup RenderScript \
                 -samplegroup Security \
                 -samplegroup Sensors \
                 -samplegroup System \
                 -samplegroup Testing \
                 -samplegroup UI \
                 -samplegroup Views \
                 -samplegroup Wearable

## SDK version identifiers used in the published docs
  # major[.minor] version for current SDK. (full releases only)
framework_docs_SDK_VERSION:=7.0
  # release version (ie "Release x")  (full releases only)
framework_docs_SDK_REL_ID:=1

framework_docs_LOCAL_DROIDDOC_OPTIONS += \
		-hdf dac true \
		-hdf sdk.codename O \
		-hdf sdk.preview.version 1 \
		-hdf sdk.version $(framework_docs_SDK_VERSION) \
		-hdf sdk.rel.id $(framework_docs_SDK_REL_ID) \
		-hdf sdk.preview 0 \
		-resourcesdir $(LOCAL_PATH)/docs/html/reference/images/ \
		-resourcesoutdir reference/android/images/

# Federate Support Library references against local API file.
framework_docs_LOCAL_DROIDDOC_OPTIONS += \
		-federate SupportLib https://developer.android.com \
		-federationapi SupportLib prebuilts/sdk/current/support-api.txt

# Federate AndroidX references against local API file.
framework_docs_LOCAL_DROIDDOC_OPTIONS += \
		-federate AndroidX https://developer.android.com \
		-federationapi AndroidX prebuilts/sdk/current/androidx-api.txt

# Get the highest numbered api txt for the given api level.
# $(1): the api level (e.g. public, system)
define highest_sdk_txt
$(HISTORICAL_SDK_VERSIONS_ROOT)/$(lastword $(call numerically_sort, \
    $(patsubst \
        $(HISTORICAL_SDK_VERSIONS_ROOT)/%,\
        %,\
        $(wildcard $(HISTORICAL_SDK_VERSIONS_ROOT)/*/$(1)/api/android.txt)\
    ) \
))
endef

# ====  Public API diff ===========================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES := $(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES := $(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS := $(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_ADDITIONAL_JAVA_DIR := $(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES := \
	$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES) \
	$(INTERNAL_PLATFORM_API_FILE)

LOCAL_MODULE := offline-sdk-referenceonly

# Basename, because apidiff adds .txt internally.
LOCAL_APIDIFF_OLDAPI := $(basename $(call highest_sdk_txt,public))
LOCAL_APIDIFF_NEWAPI := $(LOCAL_PATH)/../../$(basename $(INTERNAL_PLATFORM_API_FILE))

include $(BUILD_APIDIFF)

# Hack to get diffs included in docs output
out_zip := $(OUT_DOCS)/$(LOCAL_MODULE)-docs.zip
$(out_zip): $(full_target)

# ====  System API diff ===========================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES := $(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES := $(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS := $(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_ADDITIONAL_JAVA_DIR := $(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES := \
	$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES) \
	$(INTERNAL_PLATFORM_SYSTEM_API_FILE)

LOCAL_MODULE := offline-system-sdk-referenceonly

# Basename, because apidiff adds .txt internally.
LOCAL_APIDIFF_OLDAPI := $(basename $(call highest_sdk_txt,system))
LOCAL_APIDIFF_NEWAPI := $(LOCAL_PATH)/../../$(basename $(INTERNAL_PLATFORM_SYSTEM_API_FILE))

include $(BUILD_APIDIFF)

# Hack to get diffs included in docs output
out_zip := $(OUT_DOCS)/$(LOCAL_MODULE)-docs.zip
$(out_zip): $(full_target)

$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE))
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_API_FILE))
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_TEST_API_FILE))

# sdk.atree needs to copy the whole dir: $(OUT_DOCS)/offline-sdk to the final zip.
# So keep offline-sdk-timestamp target here, and unzip offline-sdk-docs.zip to
# $(OUT_DOCS)/offline-sdk.
$(OUT_DOCS)/offline-sdk-timestamp: $(OUT_DOCS)/offline-sdk-docs-docs.zip
	$(hide) rm -rf $(OUT_DOCS)/offline-sdk
	$(hide) mkdir -p $(OUT_DOCS)/offline-sdk
	( unzip -qo $< -d $(OUT_DOCS)/offline-sdk && touch -f $@ ) || exit 1

# ==== hiddenapi lists =======================================
$(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST): \
    .KATI_IMPLICIT_OUTPUTS := \
        $(INTERNAL_PLATFORM_HIDDENAPI_LIGHT_GREYLIST) \
        $(INTERNAL_PLATFORM_HIDDENAPI_DARK_GREYLIST) \
        $(INTERNAL_PLATFORM_HIDDENAPI_BLACKLIST)
$(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST): \
    frameworks/base/tools/hiddenapi/generate_hiddenapi_lists.py \
    frameworks/base/config/hiddenapi-light-greylist.txt \
    frameworks/base/config/hiddenapi-vendor-list.txt \
    frameworks/base/config/hiddenapi-dark-greylist.txt \
    frameworks/base/config/hiddenapi-force-blacklist.txt \
    $(INTERNAL_PLATFORM_HIDDENAPI_PUBLIC_LIST) \
    $(INTERNAL_PLATFORM_HIDDENAPI_PRIVATE_LIST) \
    $(INTERNAL_PLATFORM_REMOVED_DEX_API_FILE)
	frameworks/base/tools/hiddenapi/generate_hiddenapi_lists.py \
	    --input-public $(INTERNAL_PLATFORM_HIDDENAPI_PUBLIC_LIST) \
	    --input-private $(INTERNAL_PLATFORM_HIDDENAPI_PRIVATE_LIST) \
	    --input-whitelists $(PRIVATE_WHITELIST_INPUTS) \
	    --input-light-greylists \
	        frameworks/base/config/hiddenapi-light-greylist.txt \
	        frameworks/base/config/hiddenapi-vendor-list.txt \
	        <(comm -12 <(sort $(INTERNAL_PLATFORM_REMOVED_DEX_API_FILE)) \
	                   $(INTERNAL_PLATFORM_HIDDENAPI_PRIVATE_LIST)) \
	        $(PRIVATE_GREYLIST_INPUTS) \
	    --input-dark-greylists frameworks/base/config/hiddenapi-dark-greylist.txt \
	    --input-blacklists frameworks/base/config/hiddenapi-force-blacklist.txt \
	    --output-whitelist $(INTERNAL_PLATFORM_HIDDENAPI_WHITELIST) \
	    --output-light-greylist $(INTERNAL_PLATFORM_HIDDENAPI_LIGHT_GREYLIST) \
	    --output-dark-greylist $(INTERNAL_PLATFORM_HIDDENAPI_DARK_GREYLIST) \
	    --output-blacklist $(INTERNAL_PLATFORM_HIDDENAPI_BLACKLIST)

# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif

endif # ANDROID_BUILD_EMBEDDED
