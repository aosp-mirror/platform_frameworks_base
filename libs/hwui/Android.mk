LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

# Only build libhwui when USE_OPENGL_RENDERER is
# defined in the current device/board configuration
ifeq ($(USE_OPENGL_RENDERER),true)
    LOCAL_MODULE_CLASS := SHARED_LIBRARIES
    LOCAL_MODULE := libhwui
    LOCAL_MODULE_TAGS := optional

    include $(LOCAL_PATH)/Android.common.mk

    include $(BUILD_SHARED_LIBRARY)

    include $(call all-makefiles-under,$(LOCAL_PATH))
endif
