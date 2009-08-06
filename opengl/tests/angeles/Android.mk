# Copyright 2006 The Android Open Source Project

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)
LOCAL_SRC_FILES:= app-linux.cpp demo.c.arm
LOCAL_SHARED_LIBRARIES := libEGL libGLESv1_CM libui
LOCAL_MODULE:= angeles
LOCAL_MODULE_TAGS := optional
include $(BUILD_EXECUTABLE)


include $(CLEAR_VARS)
LOCAL_SRC_FILES:= gpustate.c
LOCAL_SHARED_LIBRARIES := libEGL libGLESv1_CM
LOCAL_MODULE:= gpustate
LOCAL_MODULE_TAGS := optional
include $(BUILD_EXECUTABLE)
