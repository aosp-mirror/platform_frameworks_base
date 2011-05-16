LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        AVCDecoder.cpp \
        src/avcdec_api.cpp \
        src/avc_bitstream.cpp \
        src/header.cpp \
        src/itrans.cpp \
        src/pred_inter.cpp \
        src/pred_intra.cpp \
        src/residual.cpp \
        src/slice.cpp \
        src/vlc.cpp

LOCAL_MODULE := libstagefright_avcdec

LOCAL_C_INCLUDES := \
        $(LOCAL_PATH)/src \
        $(LOCAL_PATH)/include \
        $(LOCAL_PATH)/../common/include \
        $(TOP)/frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \

LOCAL_CFLAGS := -DOSCL_IMPORT_REF= -DOSCL_UNUSED_ARG= -DOSCL_EXPORT_REF=

include $(BUILD_STATIC_LIBRARY)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftAVC.cpp

LOCAL_C_INCLUDES := \
        $(LOCAL_PATH)/src \
        $(LOCAL_PATH)/include \
        $(LOCAL_PATH)/../common/include \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \

LOCAL_CFLAGS := -DOSCL_IMPORT_REF=

LOCAL_STATIC_LIBRARIES := \
        libstagefright_avcdec

LOCAL_SHARED_LIBRARIES := \
        libstagefright_avc_common \
        libstagefright libstagefright_omx libstagefright_foundation libutils

LOCAL_MODULE := libstagefright_soft_avcdec
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

