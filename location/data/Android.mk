#
# Install 3 sample gps files (nmea, location, and properties)
# for use with the SDK 
#

# where to install the sample files on the device
# 
local_target_dir := $(TARGET_OUT_DATA)/location

########################
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := nmea

LOCAL_MODULE_TAGS := development

LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(local_target_dir)/gps

LOCAL_SRC_FILES := $(LOCAL_MODULE)

include $(BUILD_PREBUILT)

########################
include $(CLEAR_VARS)

LOCAL_MODULE := location

LOCAL_MODULE_TAGS := development

LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(local_target_dir)/gps

LOCAL_SRC_FILES := $(LOCAL_MODULE)

include $(BUILD_PREBUILT)

########################
include $(CLEAR_VARS)

LOCAL_MODULE := properties

LOCAL_MODULE_TAGS := development

LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(local_target_dir)/gps

LOCAL_SRC_FILES := $(LOCAL_MODULE)

include $(BUILD_PREBUILT)

########################
