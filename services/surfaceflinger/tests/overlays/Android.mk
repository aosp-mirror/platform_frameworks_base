LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	overlays.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
    libui \
    libsurfaceflinger_client

LOCAL_MODULE:= test-overlays

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
