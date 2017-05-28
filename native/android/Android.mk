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
    hardware_buffer_jni.cpp \
    input.cpp \
    looper.cpp \
    native_activity.cpp \
    native_window_jni.cpp \
    net.c \
    obb.cpp \
    sensor.cpp \
    sharedmem.cpp \
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
    libsensor \
    libandroid_runtime \
    libnetd_client \

LOCAL_STATIC_LIBRARIES := \
    libstorage \
    libarect \

LOCAL_WHOLE_STATIC_LIBRARIES := \
    libnativewindow

LOCAL_C_INCLUDES += \
    frameworks/base/native/include \
    frameworks/base/core/jni/android \
    bionic/libc/dns/include \
    system/netd/include \

LOCAL_EXPORT_STATIC_LIBRARY_HEADERS := \
    libarect \
    libnativewindow \

LOCAL_MODULE := libandroid

LOCAL_CFLAGS += $(common_cflags)

include $(BUILD_SHARED_LIBRARY)
