ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	android_media_MediaPlayer.cpp \
	android_media_MediaRecorder.cpp \
	android_media_MediaScanner.cpp \
	android_media_MediaMetadataRetriever.cpp \
	android_media_AmrInputStream.cpp

LOCAL_SHARED_LIBRARIES := \
	libopencoreplayer \
	libopencoreauthor \
	libandroid_runtime \
	libnativehelper \
	libcutils \
	libutils \
	libmedia \
	libsgl \
	libui

LOCAL_STATIC_LIBRARIES :=

LOCAL_C_INCLUDES += \
	external/tremor/Tremor \
	$(PV_INCLUDES) \
	$(JNI_H_INCLUDE) \
	$(call include-path-for, corecg graphics)

LOCAL_CFLAGS +=

LOCAL_LDLIBS := -lpthread

LOCAL_MODULE:= libmedia_jni

include $(BUILD_SHARED_LIBRARY)

# build libsoundpool.so
include $(LOCAL_PATH)/soundpool/Android.mk
endif
