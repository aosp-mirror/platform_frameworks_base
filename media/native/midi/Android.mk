LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	../../java/android/media/midi/IMidiDeviceServer.aidl \
	midi.cpp

LOCAL_AIDL_INCLUDES := \
	$(FRAMEWORKS_BASE_JAVA_SRC_DIRS) \
	frameworks/native/aidl/binder

LOCAL_CFLAGS += -Wall -Werror -O0

LOCAL_MODULE := libmidi
LOCAL_MODULE_TAGS := optional

LOCAL_SHARED_LIBRARIES := liblog libbinder libutils libmedia

include $(BUILD_SHARED_LIBRARY)
