LOCAL_PATH:= $(call my-dir)

# Set USE_CAMERA_STUB if you don't want to use the hardware camera.

# force these builds to use camera stub only
ifneq ($(filter sooner generic sim,$(TARGET_DEVICE)),)
  USE_CAMERA_STUB:=true
endif

ifeq ($(USE_CAMERA_STUB),)
  USE_CAMERA_STUB:=false
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
else
LOCAL_SHARED_LIBRARIES += libcamera 
endif

include $(BUILD_SHARED_LIBRARY)
