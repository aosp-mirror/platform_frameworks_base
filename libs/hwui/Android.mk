LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	UIMatrix.cpp \
	UIOpenGLRenderer.cpp

LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_SHARED_LIBRARIES := libcutils libutils libGLESv2
LOCAL_MODULE:= libhwui
LOCAL_PRELINK_MODULE := false

include $(BUILD_SHARED_LIBRARY)
