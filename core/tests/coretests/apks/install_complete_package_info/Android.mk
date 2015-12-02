LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := install_complete_package_info
#LOCAL_MANIFEST_FILE := api_test/AndroidManifest.xml

include $(FrameworkCoreTests_BUILD_PACKAGE)
#include $(BUILD_PACKAGE)

