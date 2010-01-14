ifneq ($(TARGET_SIMULATOR),true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

ifdef BOARD_WLAN_DEVICE
LOCAL_CFLAGS := -DFWDUMP_$(BOARD_WLAN_DEVICE)
endif

LOCAL_SRC_FILES := dumpstate.c utils.c

LOCAL_MODULE := dumpstate

LOCAL_SHARED_LIBRARIES := libcutils

include $(BUILD_EXECUTABLE)

endif
