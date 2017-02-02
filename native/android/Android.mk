BASE_PATH := $(call my-dir)
LOCAL_PATH:= $(call my-dir)

common_cflags := -Wall -Werror -Wunused -Wunreachable-code

include $(CLEAR_VARS)

# our source files
#
LOCAL_SRC_FILES:= \
    asset_manager.cpp \
    choreographer.cpp \
    configuration.cpp \
    hardware_buffer.cpp \
    input.cpp \
    looper.cpp \
    native_activity.cpp \
    native_window.cpp \
    net.c \
    obb.cpp \
    sensor.cpp \
    storage_manager.cpp \
    trace.cpp \

LOCAL_SHARED_LIBRARIES := \
    liblog \
    libcutils \
    libandroidfw \
    libinput \
    libutils \
    libbinder \
    libui \
    libgui \
    libandroid_runtime \
    libnetd_client \

LOCAL_STATIC_LIBRARIES := \
    libstorage

LOCAL_C_INCLUDES += \
    frameworks/base/native/include \
    frameworks/base/core/jni/android \
    bionic/libc/dns/include \
    system/netd/include \

LOCAL_MODULE := libandroid

LOCAL_CFLAGS += $(common_cflags)

include $(BUILD_SHARED_LIBRARY)

# Network library.
include $(CLEAR_VARS)
LOCAL_MODULE := libandroid_net
LOCAL_CFLAGS := $(common_cflags)
LOCAL_SRC_FILES:= \
    net.c \

LOCAL_SHARED_LIBRARIES := \
    libnetd_client \

LOCAL_C_INCLUDES += \
    frameworks/base/native/include \
    bionic/libc/dns/include \
    system/netd/include \

include $(BUILD_SHARED_LIBRARY)
