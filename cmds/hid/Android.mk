# Copyright 2015 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := hid
LOCAL_JNI_SHARED_LIBRARIES := libhidcommand_jni
LOCAL_REQUIRED_MODULES := libhidcommand_jni
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := hid
LOCAL_SRC_FILES := hid
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := EXECUTABLES
include $(BUILD_PREBUILT)

include $(call all-makefiles-under,$(LOCAL_PATH))
