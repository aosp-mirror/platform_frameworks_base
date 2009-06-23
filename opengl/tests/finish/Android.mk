LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	finish.c

LOCAL_SHARED_LIBRARIES := \
	libcutils \
    libEGL \
    libGLESv1_CM \
    libui

LOCAL_MODULE:= test-opengl-finish

LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)
