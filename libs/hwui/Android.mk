LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_MODULE := libhwui

include $(LOCAL_PATH)/Android.common.mk

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
