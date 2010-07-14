LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	ISensorEventConnection.cpp \
	ISensorServer.cpp \
	Sensor.cpp \
	SensorChannel.cpp \
	SensorEventQueue.cpp \
	SensorManager.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
	libhardware \
	libhardware_legacy

LOCAL_MODULE:= libgui

ifeq ($(TARGET_SIMULATOR),true)
    LOCAL_LDLIBS += -lpthread
endif

include $(BUILD_SHARED_LIBRARY)
