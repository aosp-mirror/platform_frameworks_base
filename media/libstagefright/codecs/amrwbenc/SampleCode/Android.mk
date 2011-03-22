LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    AMRWB_E_SAMPLE.c \
    ../../common/cmnMemory.c

LOCAL_MODULE_TAGS := debug
LOCAL_MODULE := AMRWBEncTest

LOCAL_ARM_MODE := arm

LOCAL_CFLAGS := $(VO_CFLAGS)

LOCAL_SHARED_LIBRARIES := \
    libstagefright \
    libdl

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/ \
    $(LOCAL_PATH)/../../common \
    $(LOCAL_PATH)/../../common/include

include $(BUILD_EXECUTABLE)



