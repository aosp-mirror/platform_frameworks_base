LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := FrameworkPerf

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_AAPT_FLAGS = -c 120dpi,240dpi,160dpi,161dpi,320dpi,nodpi

include $(BUILD_PACKAGE)
