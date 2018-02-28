LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := FrameworkPerf
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base
LOCAL_STATIC_JAVA_LIBRARIES := junit

LOCAL_AAPT_FLAGS = -c 120dpi,240dpi,160dpi,161dpi,320dpi,nodpi

include $(BUILD_PACKAGE)
