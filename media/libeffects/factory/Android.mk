LOCAL_PATH:= $(call my-dir)

# Effect factory library
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	EffectsFactory.c

LOCAL_SHARED_LIBRARIES := \
	libcutils

LOCAL_MODULE_PATH := $(TARGET_OUT_SHARED_LIBRARIES)
LOCAL_MODULE:= libeffects

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
LOCAL_LDLIBS += -ldl
endif

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_C_INCLUDES := \

include $(BUILD_SHARED_LIBRARY)
