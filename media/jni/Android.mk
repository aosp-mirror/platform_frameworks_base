ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	android_media_MediaPlayer.cpp \
	android_media_MediaRecorder.cpp \
	android_media_MediaScanner.cpp \
	android_media_MediaMetadataRetriever.cpp

LOCAL_SHARED_LIBRARIES := \
	libopencoreplayer \
	libopencoreauthor \
	libandroid_runtime \
	libnativehelper \
	libcutils \
	libutils \
	libmedia

LOCAL_STATIC_LIBRARIES :=

LOCAL_C_INCLUDES += \
	external/tremor/Tremor \
	$(JNI_H_INCLUDE) \
	$(call include-path-for, corecg graphics)

LOCAL_CFLAGS +=

LOCAL_LDLIBS := -lpthread

LOCAL_MODULE:= libmedia_jni

include $(BUILD_SHARED_LIBRARY)
endif
