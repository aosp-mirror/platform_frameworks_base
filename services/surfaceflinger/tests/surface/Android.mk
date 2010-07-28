LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	surface.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
    libui \
    libsurfaceflinger_client

LOCAL_MODULE:= test-surface

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
