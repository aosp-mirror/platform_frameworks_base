LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                     \
        ColorConverter.cpp            \
        SoftwareRenderer.cpp

LOCAL_C_INCLUDES := \
        $(TOP)/frameworks/base/include/media/stagefright/openmax \
        $(TOP)/hardware/msm7k

ifneq ($(filter crespo crespo4g,$(TARGET_DEVICE)),)
LOCAL_CFLAGS += -DTHIS_IS_CRESPO=1
endif

LOCAL_MODULE:= libstagefright_color_conversion

include $(BUILD_STATIC_LIBRARY)
