LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := CameraPrewarmTest
LOCAL_SDK_VERSION := current

LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
