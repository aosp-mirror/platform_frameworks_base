LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    G711Decoder.cpp

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \

LOCAL_MODULE := libstagefright_g711dec

include $(BUILD_STATIC_LIBRARY)
