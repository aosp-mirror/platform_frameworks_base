LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

ifneq ($(BUILD_WITHOUT_PV),true)

LOCAL_SRC_FILES:= \
	android_media_MediaPlayer.cpp \
	android_media_MediaRecorder.cpp \
	android_media_MediaScanner.cpp \
	android_media_MediaMetadataRetriever.cpp \
	android_media_AmrInputStream.cpp \
	android_media_ResampleInputStream.cpp

LOCAL_SHARED_LIBRARIES := \
	libopencore_player \
	libopencore_author \
	libandroid_runtime \
	libnativehelper \
	libcutils \
	libutils \
	libmedia \
	libsgl \
	libui \
	libomx_amrenc_sharedlibrary

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

endif

# build libsoundpool.so
include $(LOCAL_PATH)/soundpool/Android.mk
