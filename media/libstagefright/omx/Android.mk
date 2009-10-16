LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Set up the OpenCore variables.
include external/opencore/Config.mk
LOCAL_C_INCLUDES := $(PV_INCLUDES)
LOCAL_CFLAGS := $(PV_CFLAGS_MINUS_VISIBILITY)

LOCAL_C_INCLUDES += $(TOP)/hardware/ti/omap3/liboverlay
LOCAL_C_INCLUDES += $(JNI_H_INCLUDE)

LOCAL_SRC_FILES:=                 \
        ColorConverter.cpp        \
	OMX.cpp                   \
        OMXNodeInstance.cpp       \
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

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
        LOCAL_LDLIBS += -lpthread
endif

LOCAL_PRELINK_MODULE:= false

LOCAL_MODULE:= libstagefright_omx

include $(BUILD_SHARED_LIBRARY)
