# LOCAL_PATH is not the current directory of this file.
# If you need the local path, use CAR_SYSUI_PATH.
# This tweak ensures that the resource overlay that is device-specific still works
# which requires that LOCAL_PATH match the original path (which must be frameworks/base/packages/SystemUI).
#
# For clarity, we also define SYSTEM_UI_AOSP_PATH to frameworks/base/packages/SystemUI and refer to that
SYSTEM_UI_AOSP_PATH := frameworks/base/packages/SystemUI
SYSTEM_UI_CAR_PATH := frameworks/base/packages/CarSystemUI
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true

LOCAL_MODULE_TAGS := optional

# The same as SYSTEM_UI_AOSP_PATH but based on the value of LOCAL_PATH which is
# frameworks/base/packages/CarSystemUI.
RELATIVE_SYSTEM_UI_AOSP_PATH := ../../../../$(SYSTEM_UI_AOSP_PATH)


LOCAL_SRC_FILES :=  \
    $(call all-java-files-under, src) \
    $(call all-Iaidl-files-under, src) \
    $(call all-java-files-under, $(RELATIVE_SYSTEM_UI_AOSP_PATH)/src) \
    $(call all-Iaidl-files-under, $(RELATIVE_SYSTEM_UI_AOSP_PATH)/src)

LOCAL_STATIC_ANDROID_LIBRARIES := \
    SystemUIPluginLib \
    SystemUISharedLib \
    androidx.car_car \
    androidx.legacy_legacy-support-v4 \
    androidx.recyclerview_recyclerview \
    androidx.preference_preference \
    androidx.appcompat_appcompat \
    androidx.mediarouter_mediarouter \
    androidx.palette_palette \
    androidx.legacy_legacy-preference-v14 \
    androidx.leanback_leanback \
    androidx.slice_slice-core \
    androidx.slice_slice-view \
    androidx.slice_slice-builders \
    androidx.arch.core_core-runtime \
    androidx.lifecycle_lifecycle-extensions \

LOCAL_STATIC_JAVA_LIBRARIES := \
    SystemUI-tags \
    SystemUI-proto

LOCAL_JAVA_LIBRARIES := telephony-common \
    android.car

LOCAL_FULL_LIBS_MANIFEST_FILES := $(SYSTEM_UI_AOSP_PATH)/AndroidManifest.xml
LOCAL_MANIFEST_FILE := AndroidManifest.xml

LOCAL_MODULE_OWNER := google
LOCAL_PACKAGE_NAME := CarSystemUI
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_FLAG_FILES := $(RELATIVE_SYSTEM_UI_AOSP_PATH)/proguard.flags \
    proguard.flags

LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/res \
    $(SYSTEM_UI_AOSP_PATH)/res-keyguard \
    $(SYSTEM_UI_AOSP_PATH)/res

ifneq ($(INCREMENTAL_BUILDS),)
    LOCAL_PROGUARD_ENABLED := disabled
    LOCAL_JACK_ENABLED := incremental
endif

LOCAL_DX_FLAGS := --multi-dex
LOCAL_JACK_FLAGS := --multi-dex native

include frameworks/base/packages/SettingsLib/common.mk

LOCAL_OVERRIDES_PACKAGES := SystemUI

LOCAL_AAPT_FLAGS := --extra-packages com.android.keyguard

include $(BUILD_PACKAGE)

include $(call all-makefiles-under, $(SYSTEM_UI_CAR_PATH))
