LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

ifeq ($(TARGET_ARCH),arm)

LOCAL_SRC_FILES:= \
    src/WVMDrmPlugin.cpp \
    src/WVMLogging.cpp

LOCAL_C_INCLUDES:= \
    bionic \
    bionic/libstdc++/include \
    external/stlport/stlport \
    vendor/widevine/proprietary/include \
    frameworks/base/drm/libdrmframework/plugins/widevine/include

LOCAL_MODULE := libdrmwvmplugin
LOCAL_MODULE_PATH := $(TARGET_OUT_SHARED_LIBRARIES)/drm

LOCAL_STATIC_LIBRARIES := \
    libdrmframeworkcommon

LOCAL_SHARED_LIBRARIES := \
    libutils              \
    libcutils             \
    libstlport            \
    libz                  \
    libwvdrm              \
    libWVStreamControlAPI

ifeq ($(TARGET_SIMULATOR),true)
 LOCAL_LDLIBS += -ldl
else
 LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_PRELINK_MODULE := false

LOCAL_C_INCLUDES += \
    $(TOP)/frameworks/base/drm/libdrmframework/include \
    $(TOP)/frameworks/base/drm/libdrmframework/plugins/common/include \
    $(TOP)/frameworks/base/include

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

endif
