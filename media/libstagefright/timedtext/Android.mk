LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                 \
        TextDescriptions.cpp      \
        TimedTextDriver.cpp       \
        TimedTextInBandSource.cpp \
        TimedTextSource.cpp       \
        TimedTextSRTSource.cpp    \
        TimedTextPlayer.cpp

LOCAL_CFLAGS += -Wno-multichar
LOCAL_C_INCLUDES:= \
        $(JNI_H_INCLUDE) \
        $(TOP)/frameworks/base/media/libstagefright \
        $(TOP)/frameworks/base/include/media/stagefright/openmax

LOCAL_MODULE:= libstagefright_timedtext

include $(BUILD_STATIC_LIBRARY)
