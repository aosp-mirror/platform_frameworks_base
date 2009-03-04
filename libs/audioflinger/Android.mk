LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    AudioHardwareGeneric.cpp \
    AudioHardwareStub.cpp \
    AudioDumpInterface.cpp \
    AudioHardwareInterface.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libmedia \
    libhardware_legacy

ifeq ($(strip $(BOARD_USES_GENERIC_AUDIO)),true)
  LOCAL_CFLAGS += -DGENERIC_AUDIO
endif

LOCAL_MODULE:= libaudiointerface

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    AudioFlinger.cpp            \
    AudioMixer.cpp.arm          \
    AudioResampler.cpp.arm      \
    AudioResamplerSinc.cpp.arm  \
    AudioResamplerCubic.cpp.arm

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libmedia \
    libhardware_legacy

ifeq ($(strip $(BOARD_USES_GENERIC_AUDIO)),true)
  LOCAL_STATIC_LIBRARIES += libaudiointerface
else
  LOCAL_SHARED_LIBRARIES += libaudio
endif

LOCAL_MODULE:= libaudioflinger

ifeq ($(BOARD_HAVE_BLUETOOTH),true)
  LOCAL_SRC_FILES += A2dpAudioInterface.cpp
  LOCAL_SHARED_LIBRARIES += liba2dp
  LOCAL_CFLAGS += -DWITH_BLUETOOTH -DWITH_A2DP
  LOCAL_C_INCLUDES += $(call include-path-for, bluez-libs)
  LOCAL_C_INCLUDES += $(call include-path-for, bluez-utils)
endif

include $(BUILD_SHARED_LIBRARY)
