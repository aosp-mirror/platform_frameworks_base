LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true

LOCAL_AAPT2_ONLY := true

LOCAL_MODULE := SettingsLib

LOCAL_JAVA_LIBRARIES := \
    android-support-annotations

LOCAL_SHARED_ANDROID_LIBRARIES := \
    android-support-v4 \
    android-support-v7-recyclerview \
    android-support-v7-preference \
    android-support-v7-appcompat \
    android-arch-lifecycle-runtime

LOCAL_SHARED_JAVA_LIBRARIES := \
    android-arch-lifecycle-common

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_JAR_EXCLUDE_FILES := none

LOCAL_SRC_FILES := $(call all-java-files-under, src)

include $(BUILD_STATIC_JAVA_LIBRARY)

# For the test package.
include $(call all-makefiles-under, $(LOCAL_PATH))
