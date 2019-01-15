LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := install_verifier_good

LOCAL_USE_AAPT2 := true

include $(FrameworkCoreTests_BUILD_PACKAGE)
