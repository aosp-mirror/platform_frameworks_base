#
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
#

LOCAL_PATH := $(my-dir)

###########################################################
# Variant: Privileged app upgrade

include $(CLEAR_VARS)
# this needs to be a privileged application
LOCAL_PRIVILEGED_MODULE := true

LOCAL_MODULE_TAGS := optional
LOCAL_SDK_VERSION := current
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

LOCAL_PACKAGE_NAME := CtsShimPrivUpgrade

LOCAL_MANIFEST_FILE := shim_priv_upgrade/AndroidManifest.xml

LOCAL_MULTILIB := both
LOCAL_JNI_SHARED_LIBRARIES := libshim_jni

include $(BUILD_PACKAGE)
my_shim_priv_upgrade_apk := $(LOCAL_BUILT_MODULE)

###########################################################
# Variant: Privileged app

include $(CLEAR_VARS)
# this needs to be a privileged application
LOCAL_PRIVILEGED_MODULE := true

LOCAL_MODULE_TAGS := optional
LOCAL_SDK_VERSION := current
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

LOCAL_PACKAGE_NAME := CtsShimPriv

# Generate the upgrade key by taking the hash of the built CtsShimPrivUpgrade apk
gen := $(call intermediates-dir-for,APPS,$(LOCAL_PACKAGE_NAME),,true)/AndroidManifest.xml
$(gen): PRIVATE_CUSTOM_TOOL = sed -e "s/__HASH__/`sha512sum $(PRIVATE_INPUT_APK) | cut -d' ' -f1`/" $< >$@
$(gen): PRIVATE_INPUT_APK := $(my_shim_priv_upgrade_apk)
$(gen): $(LOCAL_PATH)/shim_priv/AndroidManifest.xml $(my_shim_priv_upgrade_apk)
	$(transform-generated-source)

my_shim_priv_upgrade_apk :=

LOCAL_FULL_MANIFEST_FILE := $(gen)

LOCAL_MULTILIB := both
LOCAL_JNI_SHARED_LIBRARIES := libshim_jni

include $(BUILD_PACKAGE)

###########################################################
# Variant: Privileged app upgrade w/ the wrong SHA

include $(CLEAR_VARS)
# this needs to be a privileged application
LOCAL_PRIVILEGED_MODULE := true

LOCAL_MODULE_TAGS := optional
LOCAL_SDK_VERSION := current
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false
# anything to make this package's SHA different from CtsShimPrivUpgrade
LOCAL_AAPT_FLAGS := --version-name WrongSHA

LOCAL_PACKAGE_NAME := CtsShimPrivUpgradeWrongSHA

LOCAL_MANIFEST_FILE := shim_priv_upgrade/AndroidManifest.xml

LOCAL_MULTILIB := both
LOCAL_JNI_SHARED_LIBRARIES := libshim_jni

include $(BUILD_PACKAGE)


###########################################################
# Variant: System app

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_SDK_VERSION := current
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_DEX_PREOPT := false

LOCAL_PACKAGE_NAME := CtsShim

LOCAL_MANIFEST_FILE := shim/AndroidManifest.xml

include $(BUILD_PACKAGE)

###########################################################
include $(call all-makefiles-under,$(LOCAL_PATH))
