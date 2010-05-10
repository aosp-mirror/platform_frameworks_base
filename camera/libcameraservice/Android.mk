LOCAL_PATH:= $(call my-dir)

# Set USE_CAMERA_STUB if you want to use the fake camera.
# Set USE_CAMERA_HARDWARE if you want to use the hardware camera.
# For emulator or simulator builds, we use the fake camera only by default.

ifneq ($(filter sooner generic sim,$(TARGET_DEVICE)),)
    ifeq ($(USE_CAMERA_STUB),)
        USE_CAMERA_STUB:=true
    endif
    ifeq ($(USE_CAMERA_HARDWARE),)
        USE_CAMERA_HARDWARE:=false
    endif
else
# force USE_CAMERA_STUB for testing temporarily
#    ifeq ($(USE_CAMERA_STUB),)
        USE_CAMERA_STUB:=true
#    endif
    ifeq ($(USE_CAMERA_HARDWARE),)
        USE_CAMERA_HARDWARE:=true
    endif
endif

ifeq ($(USE_CAMERA_STUB),true)
#
# libcamerastub
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    CameraHardwareStub.cpp      \
    FakeCamera.cpp

LOCAL_MODULE:= libcamerastub

ifeq ($(TARGET_SIMULATOR),true)
LOCAL_CFLAGS += -DSINGLE_PROCESS
endif

LOCAL_SHARED_LIBRARIES:= libui

include $(BUILD_STATIC_LIBRARY)
endif # USE_CAMERA_STUB

#
# libcameraservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    CameraService.cpp

LOCAL_SHARED_LIBRARIES:= \
    libui \
    libutils \
    libbinder \
    libcutils \
    libmedia \
    libcamera_client \
    libsurfaceflinger_client

LOCAL_MODULE:= libcameraservice

ifeq ($(TARGET_SIMULATOR),true)
LOCAL_CFLAGS += -DSINGLE_PROCESS
endif

ifeq ($(USE_CAMERA_STUB), true)
LOCAL_STATIC_LIBRARIES += libcamerastub
LOCAL_CFLAGS += -DUSE_CAMERA_STUB
endif

ifeq ($(USE_CAMERA_HARDWARE),true)
LOCAL_CFLAGS += -DUSE_CAMERA_HARDWARE
LOCAL_SHARED_LIBRARIES += libcamera 
endif

include $(BUILD_SHARED_LIBRARY)
