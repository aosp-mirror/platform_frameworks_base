LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	BlitHardware.cpp \
	Camera.cpp \
	CameraParameters.cpp \
	EGLDisplaySurface.cpp \
	EGLNativeWindowSurface.cpp \
	EventHub.cpp \
	EventRecurrence.cpp \
	KeyLayoutMap.cpp \
	KeyCharacterMap.cpp \
	ICamera.cpp \
	ICameraClient.cpp \
	ICameraService.cpp \
	ISurfaceComposer.cpp \
	ISurface.cpp \
	ISurfaceFlingerClient.cpp \
	LayerState.cpp \
	PixelFormat.cpp \
	Point.cpp \
	Rect.cpp \
	Region.cpp \
	Surface.cpp \
	SurfaceComposerClient.cpp \
	SurfaceFlingerSynchro.cpp \
	Time.cpp

LOCAL_SHARED_LIBRARIES := \
	libcorecg \
	libcutils \
	libutils \
	libpixelflinger \
	libhardware

LOCAL_MODULE:= libui

include $(BUILD_SHARED_LIBRARY)
