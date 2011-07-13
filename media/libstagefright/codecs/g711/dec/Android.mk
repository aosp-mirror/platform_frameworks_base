LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftG711.cpp

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \

LOCAL_SHARED_LIBRARIES := \
        libstagefright libstagefright_omx libstagefright_foundation libutils

LOCAL_MODULE := libstagefright_soft_g711dec
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
