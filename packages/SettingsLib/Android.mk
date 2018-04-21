LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true

LOCAL_AAPT2_ONLY := true

LOCAL_MODULE := SettingsLib

LOCAL_JAVA_LIBRARIES := \
    androidx.annotation_annotation

LOCAL_SHARED_ANDROID_LIBRARIES := \
    androidx.legacy_legacy-support-v4 \
    androidx.recyclerview_recyclerview \
    androidx.preference_preference \
    androidx.appcompat_appcompat \
    androidx.lifecycle_lifecycle-runtime

LOCAL_SHARED_JAVA_LIBRARIES := \
    androidx.lifecycle_lifecycle-common

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_JAR_EXCLUDE_FILES := none

LOCAL_SRC_FILES := $(call all-java-files-under, src)

include $(BUILD_STATIC_JAVA_LIBRARY)

# For the test package.
include $(call all-makefiles-under, $(LOCAL_PATH))
