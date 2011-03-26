LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= CameraServiceTest.cpp

LOCAL_MODULE:= CameraServiceTest

LOCAL_MODULE_TAGS := tests

LOCAL_C_INCLUDES += \
                frameworks/base/libs

LOCAL_CFLAGS :=

LOCAL_SHARED_LIBRARIES += \
		libbinder \
                libcutils \
                libutils \
                libui \
                libcamera_client \
                libgui

# Disable it because the ISurface interface may change, and before we have a
# chance to fix this test, we don't want to break normal builds.
#include $(BUILD_EXECUTABLE)
