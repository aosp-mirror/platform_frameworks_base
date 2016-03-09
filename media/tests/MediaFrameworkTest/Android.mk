LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_JAVA_LANGUAGE_VERSION := 1.8

LOCAL_STATIC_JAVA_LIBRARIES := easymocklib \
    mockito-target \
    android-support-test \
    android-ex-camera2

LOCAL_PACKAGE_NAME := mediaframeworktest

include $(BUILD_PACKAGE)
