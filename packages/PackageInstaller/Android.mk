LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
    $(call all-java-files-under, src)

LOCAL_STATIC_ANDROID_LIBRARIES += \
    androidx.car_car \
    androidx.design_design \
    androidx.transition_transition \
    androidx.core_core \
    androidx.media_media \
    androidx.legacy_legacy-support-core-utils \
    androidx.legacy_legacy-support-core-ui \
    androidx.fragment_fragment \
    androidx.appcompat_appcompat \
    androidx.preference_preference \
    androidx.recyclerview_recyclerview \
    androidx.legacy_legacy-preference-v14 \
    androidx.leanback_leanback \
    androidx.leanback_leanback-preference \
    SettingsLib

LOCAL_STATIC_JAVA_LIBRARIES := \
    xz-java \
    androidx.annotation_annotation

LOCAL_PACKAGE_NAME := PackageInstaller
LOCAL_CERTIFICATE := platform

LOCAL_PRIVILEGED_MODULE := true

LOCAL_PROGUARD_FLAG_FILES := proguard.flags

# Comment for now unitl all private API dependencies are removed
# LOCAL_SDK_VERSION := system_current
LOCAL_PRIVATE_PLATFORM_APIS := true

include $(BUILD_PACKAGE)

ifeq (PackageInstaller,$(LOCAL_PACKAGE_NAME))
    # Use the following include to make our test apk.
    ifeq (,$(ONE_SHOT_MAKEFILE))
        include $(call all-makefiles-under,$(LOCAL_PATH))
    endif
endif
