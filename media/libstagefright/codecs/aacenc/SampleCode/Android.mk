LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := 	AAC_E_SAMPLES.c

LOCAL_SRC_FILES += 	\
	../../../Common/cmnMemory.c

LOCAL_MODULE := TestvoAACEnc

LOCAL_ARM_MODE := arm

LOCAL_STATIC_LIBRARIES :=

LOCAL_SHARED_LIBRARIES := libvoAACEnc

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/ \
	$(LOCAL_PATH)/../../../Common \
	$(LOCAL_PATH)/../../../Include \

LOCAL_CFLAGS := $(VO_CFLAGS)

include $(BUILD_EXECUTABLE)
