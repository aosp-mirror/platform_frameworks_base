LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_STATIC_JAVA_LIBRARIES := easymocklib mockito-target core-tests android-support-test

LOCAL_PACKAGE_NAME := mediaframeworktest

include $(BUILD_PACKAGE)
