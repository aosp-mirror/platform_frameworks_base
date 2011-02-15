LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

ifeq ($(TARGET_ARCH),arm)

LOCAL_SRC_FILES:=           \
    WVMLogging.cpp          \
    WVMExtractorImpl.cpp    \
    WVMFileSource.cpp       \
    WVMMediaSource.cpp

LOCAL_C_INCLUDES:=                      \
    bionic                              \
    bionic/libstdc++                    \
    external/stlport/stlport            \
    vendor/widevine/proprietary/include \
    frameworks/base/media/libwvm/include

LOCAL_SHARED_LIBRARIES :=    \
    libstlport               \
    libstagefright           \
    libWVStreamControlAPI    \
    libdrmframework          \
    libcutils                \
    liblog                   \
    libutils                 \
    libz

LOCAL_MODULE := libwvm

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

endif
