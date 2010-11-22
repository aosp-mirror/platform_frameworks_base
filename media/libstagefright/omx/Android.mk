LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

ifneq ($(BUILD_WITHOUT_PV),true)
# Set up the OpenCore variables.
include external/opencore/Config.mk
LOCAL_C_INCLUDES := $(PV_INCLUDES)
LOCAL_CFLAGS := $(PV_CFLAGS_MINUS_VISIBILITY)
endif

LOCAL_C_INCLUDES += $(JNI_H_INCLUDE)

LOCAL_SRC_FILES:=                     \
	OMX.cpp                       \
        OMXComponentBase.cpp          \
        OMXNodeInstance.cpp           \
        OMXMaster.cpp

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SRC_FILES += \
        OMXPVCodecsPlugin.cpp
else
LOCAL_CFLAGS += -DNO_OPENCORE
endif

LOCAL_C_INCLUDES += $(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_SHARED_LIBRARIES :=       \
        libbinder               \
        libmedia                \
        libutils                \
        libui                   \
        libcutils               \

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SHARED_LIBRARIES += \
        libopencore_common
endif

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
        LOCAL_LDLIBS += -lpthread -ldl
endif

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_MODULE:= libstagefright_omx

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))

