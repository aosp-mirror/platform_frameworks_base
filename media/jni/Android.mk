LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    android_media_MediaPlayer.cpp \
    android_media_MediaRecorder.cpp \
    android_media_MediaScanner.cpp \
    android_media_MediaMetadataRetriever.cpp \
    android_media_ResampleInputStream.cpp

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libnativehelper \
    libutils \
    libbinder \
    libmedia \
    libskia \
    libui

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SRC_FILES += \
    android_media_AmrInputStream.cpp

LOCAL_SHARED_LIBRARIES += \
    libopencore_player          \
    libomx_amrenc_sharedlibrary
else
    LOCAL_CFLAGS += -DNO_OPENCORE
endif

LOCAL_STATIC_LIBRARIES :=

LOCAL_C_INCLUDES += \
    external/tremor/Tremor \
    frameworks/base/core/jni \
    frameworks/base/media/libmedia \
    $(PV_INCLUDES) \
    $(JNI_H_INCLUDE) \
    $(call include-path-for, corecg graphics)

LOCAL_CFLAGS +=

LOCAL_LDLIBS := -lpthread

LOCAL_MODULE:= libmedia_jni

include $(BUILD_SHARED_LIBRARY)

# build libsoundpool.so
include $(LOCAL_PATH)/soundpool/Android.mk
