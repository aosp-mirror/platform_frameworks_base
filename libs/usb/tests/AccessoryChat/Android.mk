LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := AccessoryChatGB

LOCAL_JAVA_LIBRARIES := com.android.future.usb.accessory

# Force an old SDK version to make sure we aren't using newer UsbManager APIs
LOCAL_SDK_VERSION := 8

include $(BUILD_PACKAGE)
