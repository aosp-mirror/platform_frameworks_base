LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	android_tts_SynthProxy.cpp

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE)

LOCAL_SHARED_LIBRARIES := \
	libandroid_runtime \
	libnativehelper \
	libmedia \
	libutils \
	libcutils

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += \
	libdl
endif


LOCAL_MODULE:= libttssynthproxy

LOCAL_ARM_MODE := arm

LOCAL_PRELINK_MODULE := false

include $(BUILD_SHARED_LIBRARY)

