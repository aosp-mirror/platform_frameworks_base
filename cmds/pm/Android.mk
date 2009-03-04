# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := pm
include $(BUILD_JAVA_LIBRARY)


include $(CLEAR_VARS)
ALL_PREBUILT += $(TARGET_OUT)/bin/pm
$(TARGET_OUT)/bin/pm : $(LOCAL_PATH)/pm | $(ACP)
	$(transform-prebuilt-to-target)

