LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=

LOCAL_SHARED_LIBRARIES := 

LOCAL_MODULE:= libsurfaceflinger_client

ifeq ($(TARGET_SIMULATOR),true)
    LOCAL_LDLIBS += -lpthread
endif

include $(BUILD_SHARED_LIBRARY)
