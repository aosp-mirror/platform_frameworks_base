LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
        YUVImage.cpp            \
        YUVCanvas.cpp

LOCAL_SHARED_LIBRARIES :=       \
        libcutils

LOCAL_MODULE:= libstagefright_yuv



include $(BUILD_SHARED_LIBRARY)
