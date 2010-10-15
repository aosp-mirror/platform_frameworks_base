LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    M4vH263Encoder.cpp \
    src/bitstream_io.cpp \
    src/combined_encode.cpp \
    src/datapart_encode.cpp \
    src/dct.cpp \
    src/findhalfpel.cpp \
    src/fastcodemb.cpp \
    src/fastidct.cpp \
    src/fastquant.cpp \
    src/me_utils.cpp \
    src/mp4enc_api.cpp \
    src/rate_control.cpp \
    src/motion_est.cpp \
    src/motion_comp.cpp \
    src/sad.cpp \
    src/sad_halfpel.cpp \
    src/vlc_encode.cpp \
    src/vop.cpp


LOCAL_MODULE := libstagefright_m4vh263enc

LOCAL_CFLAGS := \
    -DBX_RC \
    -DOSCL_IMPORT_REF= -DOSCL_UNUSED_ARG= -DOSCL_EXPORT_REF=

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/src \
    $(LOCAL_PATH)/include \
    $(TOP)/frameworks/base/include/media/stagefright/openmax \
    $(TOP)/frameworks/base/media/libstagefright/include

include $(BUILD_STATIC_LIBRARY)
