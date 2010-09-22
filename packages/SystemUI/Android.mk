LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files) \
    ../../../ex/carousel/java/com/android/ex/carousel/carousel.rs

LOCAL_JAVA_LIBRARIES := services

LOCAL_STATIC_JAVA_LIBRARIES := android-common-carousel

LOCAL_PACKAGE_NAME := SystemUI
LOCAL_CERTIFICATE := platform

LOCAL_PROGUARD_FLAGS := -include $(LOCAL_PATH)/proguard.flags

include $(BUILD_PACKAGE)
