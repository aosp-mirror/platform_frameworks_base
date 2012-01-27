LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	BitTube.cpp \
	DisplayEventReceiver.cpp \
	IDisplayEventConnection.cpp \
	ISensorEventConnection.cpp \
	ISensorServer.cpp \
	ISurfaceTexture.cpp \
	Sensor.cpp \
	SensorEventQueue.cpp \
	SensorManager.cpp \
	SurfaceTexture.cpp \
	SurfaceTextureClient.cpp \
	ISurfaceComposer.cpp \
	ISurface.cpp \
	ISurfaceComposerClient.cpp \
	IGraphicBufferAlloc.cpp \
	LayerState.cpp \
	Surface.cpp \
	SurfaceComposerClient.cpp \

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
	libhardware \
	libhardware_legacy \
	libui \
	libEGL \
	libGLESv2 \


LOCAL_MODULE:= libgui

ifeq ($(TARGET_BOARD_PLATFORM), omap4)
	LOCAL_CFLAGS += -DUSE_FENCE_SYNC
endif
ifeq ($(TARGET_BOARD_PLATFORM), s5pc110)
	LOCAL_CFLAGS += -DUSE_FENCE_SYNC
endif

ifeq ($(TARGET_BOARD_PLATFORM), tegra)
	LOCAL_CFLAGS += -DALLOW_DEQUEUE_CURRENT_BUFFER
endif

include $(BUILD_SHARED_LIBRARY)

ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif
