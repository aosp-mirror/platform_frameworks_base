LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                     \
        ColorConverter.cpp            \
        SoftwareRenderer.cpp

LOCAL_C_INCLUDES := \
        $(TOP)/frameworks/base/include/media/stagefright/openmax \
        $(TOP)/hardware/msm7k

LOCAL_SHARED_LIBRARIES :=       \
        libbinder               \
        libmedia                \
        libutils                \
        libui                   \
        libcutils				\
        libsurfaceflinger_client\
        libcamera_client

# ifeq ($(TARGET_BOARD_PLATFORM),msm7k)
ifeq ($(TARGET_PRODUCT),passion)
	LOCAL_CFLAGS += -DHAS_YCBCR420_SP_ADRENO
endif

LOCAL_MODULE:= libstagefright_color_conversion

include $(BUILD_SHARED_LIBRARY)
