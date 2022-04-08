LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    android_renderscript_RenderScript.cpp

LOCAL_SHARED_LIBRARIES := \
    libandroid \
    libandroid_runtime \
    libandroidfw \
    libnativehelper \
    libRS \
    libcutils \
    libhwui \
    liblog \
    libutils \
    libui \
    libgui \
    libjnigraphics

LOCAL_HEADER_LIBRARIES := \
    libbase_headers

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    frameworks/rs

LOCAL_CFLAGS += -Wno-unused-parameter
LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

LOCAL_MODULE:= librs_jni
LOCAL_MODULE_TAGS := optional
LOCAL_REQUIRED_MODULES := libRS

include $(BUILD_SHARED_LIBRARY)
