LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	android_tts_SynthProxy.cpp

LOCAL_C_INCLUDES += \
	frameworks/base/native/include \
	$(JNI_H_INCLUDE)

LOCAL_SHARED_LIBRARIES := \
	libandroid_runtime \
	libnativehelper \
	libmedia \
	libutils \
	libcutils

ifeq ($(TARGET_SIMULATOR),true)
 LOCAL_LDLIBS += -ldl
else
 LOCAL_SHARED_LIBRARIES += libdl
endif


LOCAL_MODULE:= libttssynthproxy

LOCAL_ARM_MODE := arm

include $(BUILD_SHARED_LIBRARY)

