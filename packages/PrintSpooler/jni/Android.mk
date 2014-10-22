LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    com_android_printspooler_util_BitmapSerializeUtils.cpp \

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE)

LOCAL_SHARED_LIBRARIES := \
    libnativehelper \
    libjnigraphics \
    liblog

LOCAL_MODULE := libprintspooler_jni
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
