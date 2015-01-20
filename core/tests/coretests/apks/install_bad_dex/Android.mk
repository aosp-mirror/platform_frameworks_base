LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := install_bad_dex

include $(FrameworkCoreTests_BUILD_PACKAGE)

# Override target specific variable PRIVATE_DEX_FILE to inject bad classes.dex file.
$(LOCAL_BUILT_MODULE): PRIVATE_DEX_FILE := $(LOCAL_PATH)/classes.dex
