LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	dumpsys.cpp

LOCAL_SHARED_LIBRARIES := \
	libutils \
	libbinder
	

ifeq ($(TARGET_OS),linux)
	LOCAL_CFLAGS += -DXP_UNIX
	#LOCAL_SHARED_LIBRARIES += librt
endif

LOCAL_MODULE:= dumpsys

include $(BUILD_EXECUTABLE)
