LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	swapinterval.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
    libEGL \
    libGLESv1_CM \
    libui

LOCAL_C_INCLUDES += $(call include-path-for, opengl-tests-includes)

LOCAL_MODULE:= test-opengl-swapinterval

LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)
