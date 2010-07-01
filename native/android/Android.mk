BASE_PATH := $(call my-dir)
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# our source files
#
LOCAL_SRC_FILES:= \
    activity.cpp \
    input.cpp \
    native_window.cpp

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libcutils \
    libutils \
    libbinder \
    libui

LOCAL_C_INCLUDES += \
    frameworks/base/native/include \
    frameworks/base/core/jni/android \
    dalvik/libnativehelper/include/nativehelper

LOCAL_MODULE:= libandroid

include $(BUILD_SHARED_LIBRARY)
