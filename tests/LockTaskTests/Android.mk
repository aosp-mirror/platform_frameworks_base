LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_PATH := $(PRODUCT_OUT)/system/priv-app

LOCAL_PACKAGE_NAME := LockTaskTests
LOCAL_LICENSE_KINDS := SPDX-license-identifier-Apache-2.0
LOCAL_LICENSE_CONDITIONS := notice
LOCAL_NOTICE_FILE  := $(LOCAL_PATH)/../../NOTICE
LOCAL_SDK_VERSION := current
LOCAL_CERTIFICATE := platform

LOCAL_SRC_FILES := $(call all-Iaidl-files-under, src) $(call all-java-files-under, src)

include $(BUILD_PACKAGE)

# Use the following include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))
