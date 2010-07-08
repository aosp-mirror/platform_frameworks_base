BASE_PATH := $(call my-dir)
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# our source files
#
LOCAL_SRC_FILES:= \
    threaded_app.c

LOCAL_C_INCLUDES += \
    frameworks/base/native/include \
    frameworks/base/core/jni/android \
    dalvik/libnativehelper/include/nativehelper

LOCAL_MODULE:= libthreaded_app

include $(BUILD_STATIC_LIBRARY)
