LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    clz.cpp.arm \
    DisplayHardware/DisplayHardware.cpp \
    DisplayHardware/DisplayHardwareBase.cpp \
    DisplayHardware/HWComposer.cpp \
    BlurFilter.cpp.arm \
    GLExtensions.cpp \
    Layer.cpp \
    LayerBase.cpp \
    LayerBuffer.cpp \
    LayerBlur.cpp \
    LayerDim.cpp \
    MessageQueue.cpp \
    SurfaceFlinger.cpp \
    TextureManager.cpp \
    Transform.cpp

LOCAL_CFLAGS:= -DLOG_TAG=\"SurfaceFlinger\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

ifeq ($(TARGET_BOARD_PLATFORM), omap3)
	LOCAL_CFLAGS += -DNO_RGBX_8888
endif

# need "-lrt" on Linux simulator to pick up clock_gettime
ifeq ($(TARGET_SIMULATOR),true)
	ifeq ($(HOST_OS),linux)
		LOCAL_LDLIBS += -lrt -lpthread
	endif
endif

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libpixelflinger \
	libhardware \
	libutils \
	libEGL \
	libGLESv1_CM \
	libbinder \
	libui \
	libsurfaceflinger_client

LOCAL_C_INCLUDES := \
	$(call include-path-for, corecg graphics)

LOCAL_C_INCLUDES += hardware/libhardware/modules/gralloc

LOCAL_MODULE:= libsurfaceflinger

include $(BUILD_SHARED_LIBRARY)
