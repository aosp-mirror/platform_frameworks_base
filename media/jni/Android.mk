LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    android_media_MediaPlayer.cpp \
    android_media_MediaRecorder.cpp \
    android_media_MediaScanner.cpp \
    android_media_MediaMetadataRetriever.cpp \
    android_media_ResampleInputStream.cpp \
    android_media_MediaProfiles.cpp \
    android_media_AmrInputStream.cpp \
    android_media_Utils.cpp \
    android_mtp_MtpDatabase.cpp \
    android_mtp_MtpDevice.cpp \
    android_mtp_MtpServer.cpp \

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libnativehelper \
    libutils \
    libbinder \
    libmedia \
    libskia \
    libui \
    libcutils \
    libgui \
    libstagefright \
    libcamera_client \
    libmtp \
    libusbhost \
    libexif

LOCAL_C_INCLUDES += \
    external/jhead \
    external/tremor/Tremor \
    frameworks/base/core/jni \
    frameworks/base/media/libmedia \
    frameworks/base/media/libstagefright/codecs/amrnb/enc/src \
    frameworks/base/media/libstagefright/codecs/amrnb/common \
    frameworks/base/media/libstagefright/codecs/amrnb/common/include \
    frameworks/base/media/mtp \
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
