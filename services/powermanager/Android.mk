LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	IPowerManager.cpp

LOCAL_SHARED_LIBRARIES := \
	libutils \
	libbinder

LOCAL_MODULE:= libpowermanager

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
