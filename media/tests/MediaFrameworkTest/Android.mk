LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base

LOCAL_STATIC_JAVA_LIBRARIES := \
    mockito-target-minus-junit4 \
    android-support-test \
    android-ex-camera2

LOCAL_PACKAGE_NAME := mediaframeworktest
LOCAL_PRIVATE_PLATFORM_APIS := true

include $(BUILD_PACKAGE)
