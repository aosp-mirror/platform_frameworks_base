LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    AVCEncoder.cpp \
    src/avcenc_api.cpp \
    src/bitstream_io.cpp \
    src/block.cpp \
    src/findhalfpel.cpp \
    src/header.cpp \
    src/init.cpp \
    src/intra_est.cpp \
    src/motion_comp.cpp \
    src/motion_est.cpp \
    src/rate_control.cpp \
    src/residual.cpp \
    src/sad.cpp \
    src/sad_halfpel.cpp \
    src/slice.cpp \
    src/vlc_encode.cpp


LOCAL_MODULE := libstagefright_avcenc

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/src \
    $(LOCAL_PATH)/../common/include \
    $(TOP)/frameworks/base/include/media/stagefright/openmax \
    $(TOP)/frameworks/base/media/libstagefright/include

LOCAL_CFLAGS := \
    -D__arm__ \
    -DOSCL_IMPORT_REF= -DOSCL_UNUSED_ARG= -DOSCL_EXPORT_REF=

include $(BUILD_STATIC_LIBRARY)
