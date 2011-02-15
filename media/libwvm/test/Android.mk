LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
        Testlibwvm.cpp

LOCAL_C_INCLUDES+=                      \
    bionic                              \
    vendor/widevine/proprietary/include \
    external/stlport/stlport            \
    frameworks/base/media/libstagefright

LOCAL_SHARED_LIBRARIES := \
    libstlport            \
    libdrmframework       \
    libstagefright        \
    liblog                \
    libutils              \
    libz                  \
    libdl

LOCAL_MODULE:=test-libwvm

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)

