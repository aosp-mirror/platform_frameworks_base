LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	egl_surface.cpp \
	sfsim.c

LOCAL_SHARED_LIBRARIES := \
    libGLES_CM

LOCAL_MODULE:= test-opengl-sfsim

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
