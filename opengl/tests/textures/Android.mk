LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	textures.c

LOCAL_SHARED_LIBRARIES := \
	libcutils \
    libEGL \
    libGLESv1_CM \
    libui

LOCAL_MODULE:= test-opengl-textures

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
