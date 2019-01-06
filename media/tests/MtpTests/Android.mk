LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_STATIC_JAVA_LIBRARIES := androidx.test.rules

LOCAL_PACKAGE_NAME := MtpTests
LOCAL_PRIVATE_PLATFORM_APIS := true

include $(BUILD_PACKAGE)
