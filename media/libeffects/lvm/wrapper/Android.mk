LOCAL_PATH:= $(call my-dir)

# music bundle wrapper
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_ARM_MODE := arm

LOCAL_SRC_FILES:= \
	Bundle/EffectBundle.cpp

LOCAL_MODULE:= libbundlewrapper

LOCAL_MODULE_PATH := $(TARGET_OUT_SHARED_LIBRARIES)/soundfx

LOCAL_PRELINK_MODULE := false

LOCAL_STATIC_LIBRARIES += libmusicbundle

LOCAL_SHARED_LIBRARIES := \
     libcutils \

ifeq ($(TARGET_SIMULATOR),true)
LOCAL_LDLIBS += -ldl
else
LOCAL_SHARED_LIBRARIES += libdl
endif


LOCAL_C_INCLUDES += \
	$(LOCAL_PATH)/Bundle \
	$(LOCAL_PATH)/../lib/Common/lib/ \
	$(LOCAL_PATH)/../lib/Bundle/lib/


include $(BUILD_SHARED_LIBRARY)
