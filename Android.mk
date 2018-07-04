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
    -hidePackage com.android.server \
    -since $(SRC_API_DIR)/1.xml 1 \
    -since $(SRC_API_DIR)/2.xml 2 \
    -since $(SRC_API_DIR)/3.xml 3 \
    -since $(SRC_API_DIR)/4.xml 4 \
    -since $(SRC_API_DIR)/5.xml 5 \
    -since $(SRC_API_DIR)/6.xml 6 \
    -since $(SRC_API_DIR)/7.xml 7 \
    -since $(SRC_API_DIR)/8.xml 8 \
    -since $(SRC_API_DIR)/9.xml 9 \
    -since $(SRC_API_DIR)/10.xml 10 \
    -since $(SRC_API_DIR)/11.xml 11 \
    -since $(SRC_API_DIR)/12.xml 12 \
    -since $(SRC_API_DIR)/13.xml 13 \
    -since $(SRC_API_DIR)/14.txt 14 \
    -since $(SRC_API_DIR)/15.txt 15 \
    -since $(SRC_API_DIR)/16.txt 16 \
    -since $(SRC_API_DIR)/17.txt 17 \
    -since $(SRC_API_DIR)/18.txt 18 \
    -since $(SRC_API_DIR)/19.txt 19 \
    -since $(SRC_API_DIR)/20.txt 20 \
    -since $(SRC_API_DIR)/21.txt 21 \
    -since $(SRC_API_DIR)/22.txt 22 \
    -since $(SRC_API_DIR)/23.txt 23 \
    -since $(SRC_API_DIR)/24.txt 24 \
    -since $(SRC_API_DIR)/25.txt 25 \
    -since $(SRC_API_DIR)/26.txt 26 \
    -since $(SRC_API_DIR)/27.txt 27 \
    -since $(SRC_API_DIR)/28.txt 28 \
    -werror -lerror -hide 111 -hide 113 -hide 125 -hide 126 -hide 127 -hide 128 \
    -overview $(LOCAL_PATH)/core/java/overview.html \

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

framework_metalava_docs_LOCAL_DROIDDOC_OPTIONS := \
    --manifest ./frameworks/base/core/res/AndroidManifest.xml \
    --hide-package com.android.okhttp \
    --hide-package com.android.org.conscrypt --hide-package com.android.server \
    --hide RequiresPermission \
    --hide MissingPermission --hide BroadcastBehavior \
    --hide HiddenSuperclass --hide DeprecationMismatch --hide UnavailableSymbol \
    --hide SdkConstant --hide HiddenTypeParameter --hide Todo --hide Typo \
    --include-annotation-classes tools/metalava/stub-annotations \

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

last_released_sdk_version := $(lastword $(call numerically_sort, \
			$(filter-out current, \
				$(patsubst $(SRC_API_DIR)/%.txt,%, $(wildcard $(SRC_API_DIR)/*.txt)) \
			 )\
		))

LOCAL_APIDIFF_OLDAPI := $(LOCAL_PATH)/../../$(SRC_API_DIR)/$(last_released_sdk_version)
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

last_released_sdk_version := $(lastword $(call numerically_sort, \
			$(filter-out current, \
				$(patsubst $(SRC_SYSTEM_API_DIR)/%.txt,%, $(wildcard $(SRC_SYSTEM_API_DIR)/*.txt)) \
			 )\
		))

LOCAL_APIDIFF_OLDAPI := $(LOCAL_PATH)/../../$(SRC_SYSTEM_API_DIR)/$(last_released_sdk_version)
LOCAL_APIDIFF_NEWAPI := $(LOCAL_PATH)/../../$(basename $(INTERNAL_PLATFORM_SYSTEM_API_FILE))

include $(BUILD_APIDIFF)

# Hack to get diffs included in docs output
out_zip := $(OUT_DOCS)/$(LOCAL_MODULE)-docs.zip
$(out_zip): $(full_target)

# ====  the api stubs and current.xml ===========================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-api $(INTERNAL_PLATFORM_API_FILE) \
		-removedApi $(INTERNAL_PLATFORM_REMOVED_API_FILE) \
		-nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

$(full_target): .KATI_IMPLICIT_OUTPUTS := $(INTERNAL_PLATFORM_API_FILE) \
                                          $(INTERNAL_PLATFORM_REMOVED_API_FILE)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_API_FILE))

# ====  the metalava api stubs and current.xml ===========================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := metalava-api-stubs
LOCAL_DROIDDOC_USE_METALAVA := true
LOCAL_DROIDDOC_METALAVA_PREVIOUS_API := prebuilts/sdk/api/27.txt
LOCAL_DROIDDOC_METALAVA_ANNOTATIONS_ENABLED := true
LOCAL_DROIDDOC_METALAVA_MERGE_ANNOTATIONS_DIR := tools/metalava/manual

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/metalava_android_stubs_current_intermediates/src

INTERNAL_PLATFORM_METALAVA_PUBLIC_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/metalava_public_api.txt
INTERNAL_PLATFORM_METALAVA_PUBLIC_REMOVED_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/metalava_removed.txt

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_metalava_docs_LOCAL_DROIDDOC_OPTIONS) \
		--api $(INTERNAL_PLATFORM_METALAVA_PUBLIC_API_FILE) \
		--removed-api $(INTERNAL_PLATFORM_METALAVA_PUBLIC_REMOVED_API_FILE) \
		-nodocs

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

$(full_target): .KATI_IMPLICIT_OUTPUTS := $(INTERNAL_PLATFORM_METALAVA_PUBLIC_API_FILE) \
                                          $(INTERNAL_PLATFORM_METALAVA_PUBLIC_REMOVED_API_FILE)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_METALAVA_PUBLIC_API_FILE))

# ====  the system api stubs ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := system-api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_system_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-showAnnotation android.annotation.SystemApi \
		-api $(INTERNAL_PLATFORM_SYSTEM_API_FILE) \
		-removedApi $(INTERNAL_PLATFORM_SYSTEM_REMOVED_API_FILE) \
		-exactApi $(INTERNAL_PLATFORM_SYSTEM_EXACT_API_FILE) \
		-nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

$(full_target): .KATI_IMPLICIT_OUTPUTS := $(INTERNAL_PLATFORM_SYSTEM_API_FILE) \
                                          $(INTERNAL_PLATFORM_SYSTEM_REMOVED_API_FILE) \
                                          $(INTERNAL_PLATFORM_SYSTEM_EXACT_API_FILE)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_SYSTEM_API_FILE))

# ====  the metalava system api stubs ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := metalava-system-api-stubs
LOCAL_DROIDDOC_USE_METALAVA := true
LOCAL_DROIDDOC_METALAVA_PREVIOUS_API := prebuilts/sdk/api/27.txt
LOCAL_DROIDDOC_METALAVA_ANNOTATIONS_ENABLED := true
LOCAL_DROIDDOC_METALAVA_MERGE_ANNOTATIONS_DIR := tools/metalava/manual

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/metalava_android_system_stubs_current_intermediates/src

INTERNAL_PLATFORM_METALAVA_SYSTEM_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/metalava-system-api.txt
INTERNAL_PLATFORM_METALAVA_SYSTEM_REMOVED_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/metalava-system-removed.txt

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_metalava_docs_LOCAL_DROIDDOC_OPTIONS) \
		--show-annotation android.annotation.SystemApi \
		--api $(INTERNAL_PLATFORM_METALAVA_SYSTEM_API_FILE) \
		--removed-api $(INTERNAL_PLATFORM_METALAVA_SYSTEM_REMOVED_API_FILE) \
		-nodocs

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

$(full_target): .KATI_IMPLICIT_OUTPUTS := $(INTERNAL_PLATFORM_METALAVA_SYSTEM_API_FILE) \
                                          $(INTERNAL_PLATFORM_METALAVA_SYSTEM_REMOVED_API_FILE) \
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_METALAVA_SYSTEM_API_FILE))

# ====  the test api stubs ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := test-api-stubs

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_test_stubs_current_intermediates/src

LOCAL_DROIDDOC_OPTIONS:=\
               $(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
               -referenceonly \
               -stubs $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/android_test_stubs_current_intermediates/src \
               -showAnnotation android.annotation.TestApi \
               -api $(INTERNAL_PLATFORM_TEST_API_FILE) \
               -removedApi $(INTERNAL_PLATFORM_TEST_REMOVED_API_FILE) \
               -exactApi $(INTERNAL_PLATFORM_TEST_EXACT_API_FILE) \
               -nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

$(full_target): .KATI_IMPLICIT_OUTPUTS := $(INTERNAL_PLATFORM_TEST_API_FILE) \
                                          $(INTERNAL_PLATFORM_TEST_REMOVED_API_FILE) \
                                          $(INTERNAL_PLATFORM_TEST_EXACT_API_FILE)
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_TEST_API_FILE))

# ====  the metalava test api stubs ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := metalava-test-api-stubs
LOCAL_DROIDDOC_USE_METALAVA := true
LOCAL_DROIDDOC_METALAVA_PREVIOUS_API := prebuilts/sdk/api/27.txt
LOCAL_DROIDDOC_METALAVA_ANNOTATIONS_ENABLED := true
LOCAL_DROIDDOC_METALAVA_MERGE_ANNOTATIONS_DIR := tools/metalava/manual

LOCAL_DROIDDOC_STUB_OUT_DIR := $(TARGET_OUT_COMMON_INTERMEDIATES)/JAVA_LIBRARIES/metalava_android_test_stubs_current_intermediates/src

INTERNAL_PLATFORM_METALAVA_TEST_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/metalava-test-api.txt
INTERNAL_PLATFORM_METALAVA_TEST_REMOVED_API_FILE := $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/metalava-test-removed.txt

LOCAL_DROIDDOC_OPTIONS:=\
               $(framework_metalava_docs_LOCAL_DROIDDOC_OPTIONS) \
               --show-annotation android.annotation.TestApi \
               --api $(INTERNAL_PLATFORM_METALAVA_TEST_API_FILE) \
               --removed-api $(INTERNAL_PLATFORM_METALAVA_TEST_REMOVED_API_FILE) \
               -nodocs

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

$(full_target): .KATI_IMPLICIT_OUTPUTS := $(INTERNAL_PLATFORM_METALAVA_TEST_API_FILE) \
                                          $(INTERNAL_PLATFORM_METALAVA_TEST_REMOVED_API_FILE) \
$(call dist-for-goals,sdk,$(INTERNAL_PLATFORM_METALAVA_TEST_API_FILE))

# ====  the complete hidden api list ===================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_API_CHECK_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_API_CHECK_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_API_CHECK_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := hidden-api-list

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-showUnannotated \
		-showAnnotation android.annotation.SystemApi \
		-showAnnotation android.annotation.TestApi \
		-dexApi $(INTERNAL_PLATFORM_DEX_API_FILE) \
		-privateDexApi $(INTERNAL_PLATFORM_PRIVATE_DEX_API_FILE) \
		-removedDexApi $(INTERNAL_PLATFORM_REMOVED_DEX_API_FILE) \
		-nodocs

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

$(full_target): .KATI_IMPLICIT_OUTPUTS := $(INTERNAL_PLATFORM_DEX_API_FILE) \
                                          $(INTERNAL_PLATFORM_PRIVATE_DEX_API_FILE) \
                                          $(INTERNAL_PLATFORM_REMOVED_DEX_API_FILE)

# ====  check javadoc comments but don't generate docs ========
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := doc-comment-check

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-parsecomments

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# Run this for checkbuild
checkbuild: doc-comment-check-docs
# Check comment when you are updating the API
update-api: doc-comment-check-docs

# ====  static html in the sdk ==================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := offline-sdk

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-offlinemode \
		-title "Android SDK" \
		-proofread $(OUT_DOCS)/$(LOCAL_MODULE)-proofread.txt \
		-sdkvalues $(OUT_DOCS) \
		-hdf android.whichdoc offline

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

static_doc_index_redirect := $(out_dir)/index.html
$(static_doc_index_redirect): \
	$(LOCAL_PATH)/docs/docs-preview-index.html | $(ACP)
	$(hide) mkdir -p $(dir $@)
	$(hide) $(ACP) $< $@

$(full_target): $(static_doc_index_redirect)


# ==== Public API static reference docs ==================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := offline-sdk-referenceonly

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-offlinemode \
		-title "Android SDK" \
		-proofread $(OUT_DOCS)/$(LOCAL_MODULE)-proofread.txt \
		-sdkvalues $(OUT_DOCS) \
		-hdf android.whichdoc offline \
		-referenceonly

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

static_doc_index_redirect := $(out_dir)/index.html
$(static_doc_index_redirect): $(LOCAL_PATH)/docs/docs-documentation-redirect.html
	$(copy-file-to-target)

static_doc_properties := $(out_dir)/source.properties
$(static_doc_properties): \
	$(LOCAL_PATH)/docs/source.properties | $(ACP)
	$(hide) mkdir -p $(dir $@)
	$(hide) $(ACP) $< $@

$(full_target): $(static_doc_index_redirect)
$(full_target): $(static_doc_properties)


# ==== System API static reference docs ==================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := offline-system-sdk-referenceonly

LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-hide 101 -hide 104 -hide 108 \
		-showAnnotation android.annotation.SystemApi \
		-offlinemode \
		-title "Android System SDK" \
		-proofread $(OUT_DOCS)/$(LOCAL_MODULE)-proofread.txt \
		-sdkvalues $(OUT_DOCS) \
		-hdf android.whichdoc offline \
		-referenceonly

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

static_doc_index_redirect := $(out_dir)/index.html
$(static_doc_index_redirect): $(LOCAL_PATH)/docs/docs-documentation-redirect.html
	$(copy-file-to-target)

static_doc_properties := $(out_dir)/source.properties
$(static_doc_properties): \
	$(LOCAL_PATH)/docs/source.properties | $(ACP)
	$(hide) mkdir -p $(dir $@)
	$(hide) $(ACP) $< $@

$(full_target): $(static_doc_index_redirect)
$(full_target): $(static_doc_properties)
$(full_target): $(framework_built)


# ==== docs for the web (on the androiddevdocs app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-sdk

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== docs for the web (on the androiddevdocs app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-system-api-sdk

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-showAnnotation android.annotation.SystemApi \
		-title "Android SDK - Including system APIs." \
		-toroot / \
		-hide 101 \
		-hide 104 \
		-hide 108 \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_DROIDDOC)

# ==== docs for the web (on the devsite app engine server) =======================
include $(CLEAR_VARS)
LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
# specify a second html input dir and an output path relative to OUT_DIR)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := ds

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		-devsite \
		-yamlV2 \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== docs for the web (on the devsite app engine server) =======================
include $(CLEAR_VARS)
LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
# specify a second html input dir and an output path relative to OUT_DIR)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := ds-static

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-hdf android.whichdoc online \
		-staticonly \
		-toroot / \
		-devsite \
		-ignoreJdLinks

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== generates full navtree for resolving @links in ds postprocessing ====
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := ds-ref-navtree

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-hdf android.whichdoc online \
		-toroot / \
		-atLinksNavtree \
		-navtreeonly

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== site updates for docs (on the androiddevdocs app engine server) =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_STATIC_JAVA_LIBRARIES:=$(framework_docs_LOCAL_STATIC_JAVA_LIBRARIES)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)
LOCAL_ADDITIONAL_HTML_DIR:=docs/html-intl /

LOCAL_MODULE := online-sdk-dev

LOCAL_DROIDDOC_OPTIONS:= \
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-toroot / \
		-hdf android.whichdoc online \
		$(sample_groups) \
		-hdf android.hasSamples true \
		-samplesdir $(samples_dir)

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ==== docs that have all of the stuff that's @hidden =======================
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=$(framework_docs_LOCAL_SRC_FILES)
LOCAL_GENERATED_SOURCES:=$(framework_docs_LOCAL_GENERATED_SOURCES)
LOCAL_SRCJARS:=$(framework_docs_LOCAL_SRCJARS)
LOCAL_JAVA_LIBRARIES:=$(framework_docs_LOCAL_JAVA_LIBRARIES)
LOCAL_MODULE_CLASS:=$(framework_docs_LOCAL_MODULE_CLASS)
LOCAL_DROIDDOC_SOURCE_PATH:=$(framework_docs_LOCAL_DROIDDOC_SOURCE_PATH)
LOCAL_DROIDDOC_HTML_DIR:=$(framework_docs_LOCAL_DROIDDOC_HTML_DIR)
LOCAL_ADDITIONAL_JAVA_DIR:=$(framework_docs_LOCAL_ADDITIONAL_JAVA_DIR)
LOCAL_ADDITIONAL_DEPENDENCIES:=$(framework_docs_LOCAL_ADDITIONAL_DEPENDENCIES)

LOCAL_MODULE := hidden
LOCAL_DROIDDOC_OPTIONS:=\
		$(framework_docs_LOCAL_DROIDDOC_OPTIONS) \
		-referenceonly \
		-title "Android SDK - Including hidden APIs."
#		-hidden

LOCAL_DROIDDOC_CUSTOM_TEMPLATE_DIR:=external/doclava/res/assets/templates-sdk

include $(BUILD_DROIDDOC)

# ====  java proto host library  ==============================
include $(CLEAR_VARS)
LOCAL_MODULE := platformprotos
LOCAL_PROTOC_OPTIMIZE_TYPE := full
LOCAL_PROTOC_FLAGS := \
    -Iexternal/protobuf/src
LOCAL_SOURCE_FILES_ALL_GENERATED := true
LOCAL_SRC_FILES := \
    cmds/am/proto/instrumentation_data.proto \
    cmds/statsd/src/perfetto/perfetto_config.proto \
    $(call all-proto-files-under, core/proto) \
    $(call all-proto-files-under, libs/incident/proto) \
    $(call all-proto-files-under, cmds/statsd/src)
# b/72714520
LOCAL_ERROR_PRONE_FLAGS := -Xep:MissingOverride:OFF
include $(BUILD_HOST_JAVA_LIBRARY)

# ====  java proto device library (for test only)  ==============================
include $(CLEAR_VARS)
LOCAL_MODULE := platformprotosnano
LOCAL_MODULE_TAGS := tests
LOCAL_PROTOC_OPTIMIZE_TYPE := nano
LOCAL_PROTOC_FLAGS := \
    -Iexternal/protobuf/src
LOCAL_PROTO_JAVA_OUTPUT_PARAMS := \
    store_unknown_fields = true
LOCAL_SDK_VERSION := current
LOCAL_SRC_FILES := \
    $(call all-proto-files-under, core/proto) \
    $(call all-proto-files-under, libs/incident/proto/android/os)
include $(BUILD_STATIC_JAVA_LIBRARY)


# ====  java proto device library (for test only)  ==============================
include $(CLEAR_VARS)
LOCAL_MODULE := platformprotoslite
LOCAL_MODULE_TAGS := tests
LOCAL_PROTOC_OPTIMIZE_TYPE := lite
LOCAL_PROTOC_FLAGS := \
    -Iexternal/protobuf/src
LOCAL_SRC_FILES := \
    $(call all-proto-files-under, core/proto) \
    $(call all-proto-files-under, libs/incident/proto/android/os)
# Protos have lots of MissingOverride and similar.
LOCAL_ERROR_PRONE_FLAGS := -XepDisableAllChecks
include $(BUILD_STATIC_JAVA_LIBRARY)

# ==== hiddenapi lists =======================================
include $(CLEAR_VARS)

# File names of final API lists
LOCAL_LIGHT_GREYLIST := $(INTERNAL_PLATFORM_HIDDENAPI_LIGHT_GREYLIST)
LOCAL_DARK_GREYLIST := $(INTERNAL_PLATFORM_HIDDENAPI_DARK_GREYLIST)
LOCAL_BLACKLIST := $(INTERNAL_PLATFORM_HIDDENAPI_BLACKLIST)

# File names of source files we will use to generate the final API lists.
LOCAL_SRC_GREYLIST := frameworks/base/config/hiddenapi-light-greylist.txt
LOCAL_SRC_VENDOR_LIST := frameworks/base/config/hiddenapi-vendor-list.txt
LOCAL_SRC_FORCE_BLACKLIST := frameworks/base/config/hiddenapi-force-blacklist.txt
LOCAL_SRC_PUBLIC_API := frameworks/base/config/hiddenapi-public-dex.txt
LOCAL_SRC_PRIVATE_API := frameworks/base/config/hiddenapi-private-dex.txt
LOCAL_SRC_REMOVED_API := frameworks/base/config/hiddenapi-removed-dex.txt

LOCAL_SRC_ALL := \
	$(LOCAL_SRC_GREYLIST) \
	$(LOCAL_SRC_VENDOR_LIST) \
	$(LOCAL_SRC_FORCE_BLACKLIST) \
	$(LOCAL_SRC_PUBLIC_API) \
	$(LOCAL_SRC_PRIVATE_API) \
	$(LOCAL_SRC_REMOVED_API)

define assert-has-no-overlap
if [ ! -z "`comm -12 <(sort $(1)) <(sort $(2))`" ]; then \
	echo "$(1) and $(2) should not overlap" 1>&2; \
	comm -12 <(sort $(1)) <(sort $(2)) 1>&2; \
	exit 1; \
fi
endef

define assert-is-subset
if [ ! -z "`comm -23 <(sort $(1)) <(sort $(2))`" ]; then \
	echo "$(1) must be a subset of $(2)" 1>&2; \
	comm -23 <(sort $(1)) <(sort $(2)) 1>&2; \
	exit 1; \
fi
endef

define assert-has-no-duplicates
if [ ! -z "`sort $(1) | uniq -D`" ]; then \
	echo "$(1) has duplicate entries" 1>&2; \
	sort $(1) | uniq -D 1>&2; \
	exit 1; \
fi
endef

# The following rules build API lists in the build folder.
# By not using files from the source tree, ART buildbots can mock these lists
# or have alternative rules for building them. Other rules in the build system
# should depend on the files in the build folder.

# Merge light greylist from multiple files:
#  (1) manual greylist LOCAL_SRC_GREYLIST
#  (2) list of usages from vendor apps LOCAL_SRC_VENDOR_LIST
#  (3) list of removed APIs in LOCAL_SRC_REMOVED_API
#      @removed does not imply private in Doclava. We must take the subset also
#      in LOCAL_SRC_PRIVATE_API.
#  (4) list of serialization APIs
#      Automatically adds all methods which match the signatures in
#      REGEX_SERIALIZATION. These are greylisted in order to allow applications
#      to write their own serializers.
$(LOCAL_LIGHT_GREYLIST): REGEX_SERIALIZATION := \
    "readObject\(Ljava/io/ObjectInputStream;\)V" \
    "readObjectNoData\(\)V" \
    "readResolve\(\)Ljava/lang/Object;" \
    "serialVersionUID:J" \
    "serialPersistentFields:\[Ljava/io/ObjectStreamField;" \
    "writeObject\(Ljava/io/ObjectOutputStream;\)V" \
    "writeReplace\(\)Ljava/lang/Object;"
$(LOCAL_LIGHT_GREYLIST): $(LOCAL_SRC_ALL)
	sort $(LOCAL_SRC_GREYLIST) $(LOCAL_SRC_VENDOR_LIST) \
	     <(grep -E "\->("$(subst $(space),"|",$(REGEX_SERIALIZATION))")$$" \
	               $(LOCAL_SRC_PRIVATE_API)) \
	     <(comm -12 <(sort $(LOCAL_SRC_REMOVED_API)) <(sort $(LOCAL_SRC_PRIVATE_API))) \
	     > $@
	$(call assert-has-no-duplicates,$@)
	$(call assert-is-subset,$@,$(LOCAL_SRC_PRIVATE_API))
	$(call assert-has-no-overlap,$@,$(LOCAL_SRC_FORCE_BLACKLIST))

# Generate dark greylist as remaining classes and class members in the same
# package as classes listed in the light greylist.
# The algorithm is as follows:
#   (1) extract the class descriptor from each entry in LOCAL_LIGHT_GREYLIST
#   (2) strip everything after the last forward-slash,
#       e.g. 'Lpackage/subpackage/class$inner;' turns into 'Lpackage/subpackage/'
#   (3) insert all entries from LOCAL_SRC_PRIVATE_API which begin with the package
#       name but do not contain another forward-slash in the class name, e.g.
#       matching '^Lpackage/subpackage/[^/;]*;'
#   (4) subtract entries shared with LOCAL_LIGHT_GREYLIST
$(LOCAL_DARK_GREYLIST): $(LOCAL_SRC_ALL) $(LOCAL_LIGHT_GREYLIST)
	comm -13 <(sort $(LOCAL_LIGHT_GREYLIST) $(LOCAL_SRC_FORCE_BLACKLIST)) \
	         <(cat $(LOCAL_SRC_PUBLIC_API) $(LOCAL_LIGHT_GREYLIST) | \
	               sed 's/\->.*//' | sed 's/\(.*\/\).*/\1/' | sort | uniq | \
	               while read PKG_NAME; do \
	                   grep -E "^$${PKG_NAME}[^/;]*;" $(LOCAL_SRC_PRIVATE_API); \
	               done | sort | uniq) \
	         > $@
	$(call assert-is-subset,$@,$(LOCAL_SRC_PRIVATE_API))
	$(call assert-has-no-duplicates,$@)
	$(call assert-has-no-overlap,$@,$(LOCAL_LIGHT_GREYLIST))
	$(call assert-has-no-overlap,$@,$(LOCAL_SRC_FORCE_BLACKLIST))

# Generate blacklist as private API minus (light greylist plus dark greylist).
$(LOCAL_BLACKLIST): $(LOCAL_SRC_ALL) $(LOCAL_LIGHT_GREYLIST) $(LOCAL_DARK_GREYLIST)
	comm -13 <(sort $(LOCAL_LIGHT_GREYLIST) $(LOCAL_DARK_GREYLIST)) \
	         <(sort $(LOCAL_SRC_PRIVATE_API)) \
	         > $@
	$(call assert-is-subset,$@,$(LOCAL_SRC_PRIVATE_API))
	$(call assert-has-no-duplicates,$@)
	$(call assert-has-no-overlap,$@,$(LOCAL_LIGHT_GREYLIST))
	$(call assert-has-no-overlap,$@,$(LOCAL_DARK_GREYLIST))
	$(call assert-is-subset,$(LOCAL_SRC_FORCE_BLACKLIST),$@)

# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif

endif # ANDROID_BUILD_EMBEDDED
