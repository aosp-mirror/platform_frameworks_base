LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

FrameworkCoreTests_BUILD_PACKAGE := $(LOCAL_PATH)/FrameworkCoreTests_apk.mk

# build sub packages
include $(call all-makefiles-under,$(LOCAL_PATH))
