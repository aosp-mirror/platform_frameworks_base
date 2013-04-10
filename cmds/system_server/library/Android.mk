LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	system_init.cpp

base = $(LOCAL_PATH)/../../..
native = $(LOCAL_PATH)/../../../../native

LOCAL_C_INCLUDES := \
	$(native)/services/sensorservice \
	$(native)/services/surfaceflinger \
	$(JNI_H_INCLUDE)

LOCAL_SHARED_LIBRARIES := \
	libandroid_runtime \
	libsensorservice \
	libsurfaceflinger \
	libinput \
	libutils \
	libbinder \
	libcutils \
	liblog

LOCAL_MODULE:= libsystem_server

include $(BUILD_SHARED_LIBRARY)
