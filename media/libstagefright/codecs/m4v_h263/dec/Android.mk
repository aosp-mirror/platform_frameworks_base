LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
 	src/adaptive_smooth_no_mmx.cpp \
 	src/bitstream.cpp \
 	src/block_idct.cpp \
 	src/cal_dc_scaler.cpp \
 	src/chvr_filter.cpp \
 	src/chv_filter.cpp \
 	src/combined_decode.cpp \
 	src/conceal.cpp \
 	src/datapart_decode.cpp \
 	src/dcac_prediction.cpp \
 	src/dec_pred_intra_dc.cpp \
 	src/deringing_chroma.cpp \
 	src/deringing_luma.cpp \
 	src/find_min_max.cpp \
 	src/get_pred_adv_b_add.cpp \
 	src/get_pred_outside.cpp \
 	src/idct.cpp \
 	src/idct_vca.cpp \
 	src/mb_motion_comp.cpp \
 	src/mb_utils.cpp \
 	src/packet_util.cpp \
 	src/post_filter.cpp \
 	src/post_proc_semaphore.cpp \
 	src/pp_semaphore_chroma_inter.cpp \
 	src/pp_semaphore_luma.cpp \
 	src/pvdec_api.cpp \
 	src/scaling_tab.cpp \
 	src/vlc_decode.cpp \
 	src/vlc_dequant.cpp \
 	src/vlc_tab.cpp \
 	src/vop.cpp \
 	src/zigzag_tab.cpp


LOCAL_MODULE := libstagefright_m4vh263dec

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/include \
	$(TOP)/frameworks/base/media/libstagefright/include \
	$(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_CFLAGS := -DOSCL_EXPORT_REF= -DOSCL_IMPORT_REF=

include $(BUILD_STATIC_LIBRARY)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftMPEG4.cpp

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/include \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \

LOCAL_CFLAGS := -DOSCL_EXPORT_REF= -DOSCL_IMPORT_REF=

LOCAL_STATIC_LIBRARIES := \
        libstagefright_m4vh263dec

LOCAL_SHARED_LIBRARIES := \
        libstagefright libstagefright_omx libstagefright_foundation libutils

LOCAL_MODULE := libstagefright_soft_mpeg4dec
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
