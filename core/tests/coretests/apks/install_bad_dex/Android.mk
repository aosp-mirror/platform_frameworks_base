LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := install_bad_dex

LOCAL_JAVA_RESOURCE_FILES := $(LOCAL_PATH)/classes.dex

include $(FrameworkCoreTests_BUILD_PACKAGE)
