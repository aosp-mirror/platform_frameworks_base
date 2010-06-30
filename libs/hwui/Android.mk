LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	Matrix.cpp \
	OpenGLRenderer.cpp \
	Program.cpp \
	TextureCache.cpp

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	$(LOCAL_PATH)/../../include/utils \
	external/skia/include/core \
	external/skia/include/effects \
	external/skia/include/images \
	external/skia/src/ports \
	external/skia/include/utils

LOCAL_MODULE_CLASS := SHARED_LIBRARIES
LOCAL_SHARED_LIBRARIES := libcutils libutils libGLESv2 libskia
LOCAL_MODULE:= libhwui
LOCAL_PRELINK_MODULE := false

include $(BUILD_SHARED_LIBRARY)
