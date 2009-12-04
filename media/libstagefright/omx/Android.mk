LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Set up the OpenCore variables.
include external/opencore/Config.mk
LOCAL_C_INCLUDES := $(PV_INCLUDES)
LOCAL_CFLAGS := $(PV_CFLAGS_MINUS_VISIBILITY)

LOCAL_C_INCLUDES += $(JNI_H_INCLUDE)

LOCAL_SRC_FILES:=                     \
        ColorConverter.cpp            \
	OMX.cpp                       \
        OMXComponentBase.cpp          \
        OMXNodeInstance.cpp           \
        OMXMaster.cpp                 \
        OMXSoftwareCodecsPlugin.cpp   \
        SoftwareRenderer.cpp

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SRC_FILES += \
        OMXPVCodecsPlugin.cpp
else
LOCAL_CFLAGS += -DNO_OPENCORE
endif

LOCAL_SHARED_LIBRARIES :=       \
        libbinder               \
        libmedia                \
        libutils                \
        libui                   \
        libcutils

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SHARED_LIBRARIES += \
        libopencore_common
endif

LOCAL_STATIC_LIBRARIES :=       \
        libstagefright_mp3

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
        LOCAL_LDLIBS += -lpthread -ldl
endif

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_PRELINK_MODULE:= false

LOCAL_MODULE:= libstagefright_omx

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
