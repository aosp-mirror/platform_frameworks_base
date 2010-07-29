LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_STATIC_JAVA_LIBRARIES := diff_match_patch

LOCAL_PACKAGE_NAME := DumpRenderTree2

include $(BUILD_PACKAGE)