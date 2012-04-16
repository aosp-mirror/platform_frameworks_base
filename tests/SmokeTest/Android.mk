LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

# This builds "SmokeTestApp"
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := SmokeTestApp

LOCAL_SDK_VERSION := 8

include $(BUILD_PACKAGE)

# This builds "SmokeTest"
include $(call all-makefiles-under,$(LOCAL_PATH))
