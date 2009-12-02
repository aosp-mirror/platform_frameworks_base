LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= CameraServiceTest.cpp

LOCAL_MODULE:= CameraServiceTest

LOCAL_MODULE_TAGS := tests

LOCAL_C_INCLUDES += \
                frameworks/base/libs

LOCAL_CFLAGS :=

LOCAL_SHARED_LIBRARIES += \
                libcutils \
                libutils \
                libui

include $(BUILD_EXECUTABLE)
