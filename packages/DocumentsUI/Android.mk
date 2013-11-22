LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4 guava

LOCAL_PACKAGE_NAME := DocumentsUI
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
