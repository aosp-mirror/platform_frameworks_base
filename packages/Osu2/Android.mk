LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PROGUARD_ENABLED := disabled
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := Osu2
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

include $(BUILD_PACKAGE)

########################
include $(call all-makefiles-under,$(LOCAL_PATH))
