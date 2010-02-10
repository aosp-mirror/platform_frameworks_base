LOCAL_PATH:= $(call my-dir)

#
# Set USE_CAMERA_STUB for non-emulator and non-simulator builds, if you want
# the camera service to use the fake camera.  For emulator or simulator builds,
# we always use the fake camera.

ifeq ($(USE_CAMERA_STUB),)
USE_CAMERA_STUB:=false
ifneq ($(filter sooner generic sim,$(TARGET_DEVICE)),)
USE_CAMERA_STUB:=true
endif #libcamerastub
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

LOCAL_CFLAGS += -DLOG_TAG=\"CameraService\"

ifeq ($(TARGET_SIMULATOR),true)
LOCAL_CFLAGS += -DSINGLE_PROCESS
endif

ifeq ($(USE_CAMERA_STUB), true)
LOCAL_STATIC_LIBRARIES += libcamerastub
LOCAL_CFLAGS += -include CameraHardwareStub.h
else
LOCAL_SHARED_LIBRARIES += libcamera 
endif

include $(BUILD_SHARED_LIBRARY)

