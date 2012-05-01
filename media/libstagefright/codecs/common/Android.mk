LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)



LOCAL_SRC_FILES := cmnMemory.c

LOCAL_MODULE := libstagefright_enc_common

LOCAL_ARM_MODE := arm

LOCAL_STATIC_LIBRARIES :=

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/include

LOCAL_CFLAGS := $(VO_CFLAGS)

include $(BUILD_SHARED_LIBRARY)



