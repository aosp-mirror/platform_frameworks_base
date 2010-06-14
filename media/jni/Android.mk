LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

ifneq ($(BUILD_WITHOUT_PV),true)
include external/opencore/Config.mk
endif

LOCAL_SRC_FILES:= \
    android_media_MediaPlayer.cpp \
    android_media_MediaRecorder.cpp \
    android_media_MediaScanner.cpp \
    android_media_MediaMetadataRetriever.cpp \
    android_media_ResampleInputStream.cpp \
    android_media_MediaProfiles.cpp

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libnativehelper \
    libutils \
    libbinder \
    libmedia \
    libskia \
    libui \
    libcutils \
    libsurfaceflinger_client \
    libcamera_client

ifneq ($(BUILD_WITHOUT_PV),true)

LOCAL_SRC_FILES += \
    android_media_AmrInputStream.cpp

LOCAL_SHARED_LIBRARIES += \
    libopencore_player          \
    libomx_amrenc_sharedlibrary
else
    LOCAL_CFLAGS += -DNO_OPENCORE
endif

ifeq ($(BUILD_WITH_FULL_STAGEFRIGHT),true)

LOCAL_CFLAGS += -DBUILD_WITH_FULL_STAGEFRIGHT=1

LOCAL_SHARED_LIBRARIES += \
    libstagefright

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
# build libaudioeffect_jni.so
include $(call all-makefiles-under,$(LOCAL_PATH))
