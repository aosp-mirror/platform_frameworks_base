LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
 	src/a_refl.cpp \
 	src/agc.cpp \
 	src/amrdecode.cpp \
 	src/b_cn_cod.cpp \
 	src/bgnscd.cpp \
 	src/c_g_aver.cpp \
 	src/d1035pf.cpp \
 	src/d2_11pf.cpp \
 	src/d2_9pf.cpp \
 	src/d3_14pf.cpp \
 	src/d4_17pf.cpp \
 	src/d8_31pf.cpp \
 	src/d_gain_c.cpp \
 	src/d_gain_p.cpp \
 	src/d_plsf.cpp \
 	src/d_plsf_3.cpp \
 	src/d_plsf_5.cpp \
 	src/dec_amr.cpp \
 	src/dec_gain.cpp \
 	src/dec_input_format_tab.cpp \
 	src/dec_lag3.cpp \
 	src/dec_lag6.cpp \
 	src/dtx_dec.cpp \
 	src/ec_gains.cpp \
 	src/ex_ctrl.cpp \
 	src/if2_to_ets.cpp \
 	src/int_lsf.cpp \
 	src/lsp_avg.cpp \
 	src/ph_disp.cpp \
 	src/post_pro.cpp \
 	src/preemph.cpp \
 	src/pstfilt.cpp \
 	src/qgain475_tab.cpp \
 	src/sp_dec.cpp \
 	src/wmf_to_ets.cpp

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        $(LOCAL_PATH)/src \
        $(LOCAL_PATH)/include \
        $(LOCAL_PATH)/../common/include \
        $(LOCAL_PATH)/../common

LOCAL_CFLAGS := \
        -DOSCL_UNUSED_ARG= -DOSCL_IMPORT_REF=

LOCAL_MODULE := libstagefright_amrnbdec

include $(BUILD_STATIC_LIBRARY)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftAMR.cpp

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \
        $(LOCAL_PATH)/src \
        $(LOCAL_PATH)/include \
        $(LOCAL_PATH)/../common/include \
        $(LOCAL_PATH)/../common \
        frameworks/base/media/libstagefright/codecs/amrwb/src \

LOCAL_CFLAGS := -DOSCL_IMPORT_REF=

LOCAL_STATIC_LIBRARIES := \
        libstagefright_amrnbdec libstagefright_amrwbdec

LOCAL_SHARED_LIBRARIES := \
        libstagefright_omx libstagefright_foundation libutils \
        libstagefright_amrnb_common

LOCAL_MODULE := libstagefright_soft_amrdec
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
