LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	configdump.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
    libEGL \
    libGLESv1_CM

LOCAL_MODULE:= test-opengl-configdump

LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)
