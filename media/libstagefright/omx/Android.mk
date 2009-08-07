LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Set up the OpenCore variables.
include external/opencore/Config.mk
LOCAL_C_INCLUDES := $(PV_INCLUDES)
LOCAL_CFLAGS := $(PV_CFLAGS_MINUS_VISIBILITY)

LOCAL_SRC_FILES:=                 \
	OMX.cpp                   \
        QComHardwareRenderer.cpp  \
        SoftwareRenderer.cpp      \
        TIHardwareRenderer.cpp

LOCAL_SHARED_LIBRARIES :=       \
        libbinder               \
        libmedia                \
	libutils                \
        libui                   \
        libcutils               \
        libopencore_common

LOCAL_PRELINK_MODULE:= false

LOCAL_MODULE:= libstagefright_omx

include $(BUILD_SHARED_LIBRARY)
