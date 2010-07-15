LOCAL_PATH:= $(call my-dir)

#AUDIO_POLICY_TEST := true
#ENABLE_AUDIO_DUMP := true

include $(CLEAR_VARS)


ifeq ($(AUDIO_POLICY_TEST),true)
  ENABLE_AUDIO_DUMP := true
endif


LOCAL_SRC_FILES:= \
    AudioHardwareGeneric.cpp \
    AudioHardwareStub.cpp \
    AudioHardwareInterface.cpp

ifeq ($(ENABLE_AUDIO_DUMP),true)
  LOCAL_SRC_FILES += AudioDumpInterface.cpp
  LOCAL_CFLAGS += -DENABLE_AUDIO_DUMP
endif

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libbinder \
    libmedia \
    libhardware_legacy

ifeq ($(strip $(BOARD_USES_GENERIC_AUDIO)),true)
  LOCAL_CFLAGS += -DGENERIC_AUDIO
endif

LOCAL_MODULE:= libaudiointerface

ifeq ($(BOARD_HAVE_BLUETOOTH),true)
  LOCAL_SRC_FILES += A2dpAudioInterface.cpp
  LOCAL_SHARED_LIBRARIES += liba2dp
  LOCAL_CFLAGS += -DWITH_BLUETOOTH -DWITH_A2DP
  LOCAL_C_INCLUDES += $(call include-path-for, bluez)
endif

include $(BUILD_STATIC_LIBRARY)


include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    AudioPolicyManagerBase.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libmedia

ifeq ($(TARGET_SIMULATOR),true)
 LOCAL_LDLIBS += -ldl
else
 LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_MODULE:= libaudiopolicybase

ifeq ($(BOARD_HAVE_BLUETOOTH),true)
  LOCAL_CFLAGS += -DWITH_A2DP
endif

ifeq ($(AUDIO_POLICY_TEST),true)
  LOCAL_CFLAGS += -DAUDIO_POLICY_TEST
endif

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    AudioFlinger.cpp            \
    AudioMixer.cpp.arm          \
    AudioResampler.cpp.arm      \
    AudioResamplerSinc.cpp.arm  \
    AudioResamplerCubic.cpp.arm \
    AudioPolicyService.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libbinder \
    libmedia \
    libhardware_legacy \
    libeffects

ifeq ($(strip $(BOARD_USES_GENERIC_AUDIO)),true)
  LOCAL_STATIC_LIBRARIES += libaudiointerface libaudiopolicybase
  LOCAL_CFLAGS += -DGENERIC_AUDIO
else
  LOCAL_SHARED_LIBRARIES += libaudio libaudiopolicy
endif

ifeq ($(TARGET_SIMULATOR),true)
 LOCAL_LDLIBS += -ldl
else
 LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_MODULE:= libaudioflinger

ifeq ($(BOARD_HAVE_BLUETOOTH),true)
  LOCAL_CFLAGS += -DWITH_BLUETOOTH -DWITH_A2DP
  LOCAL_SHARED_LIBRARIES += liba2dp
endif

ifeq ($(AUDIO_POLICY_TEST),true)
  LOCAL_CFLAGS += -DAUDIO_POLICY_TEST
endif

ifeq ($(TARGET_SIMULATOR),true)
    ifeq ($(HOST_OS),linux)
        LOCAL_LDLIBS += -lrt -lpthread
    endif
endif

ifeq ($(BOARD_USE_LVMX),true)
    LOCAL_CFLAGS += -DLVMX
    LOCAL_C_INCLUDES += vendor/nxp
    LOCAL_STATIC_LIBRARIES += liblifevibes
    LOCAL_SHARED_LIBRARIES += liblvmxservice
#    LOCAL_SHARED_LIBRARIES += liblvmxipc
endif

include $(BUILD_SHARED_LIBRARY)
