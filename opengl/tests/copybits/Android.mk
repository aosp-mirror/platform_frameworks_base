LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	copybits.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libhardware \
    libEGL \
    libGLESv1_CM \
    libui

LOCAL_MODULE:= test-opengl-copybits

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)

