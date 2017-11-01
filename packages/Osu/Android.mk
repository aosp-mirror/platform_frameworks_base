LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_PROGUARD_ENABLED := disabled
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SRC_FILES += \
	src/com/android/hotspot2/app/IOSUAccessor.aidl \
	src/com/android/hotspot2/flow/IFlowService.aidl

LOCAL_JAVA_LIBRARIES := telephony-common ims-common bouncycastle conscrypt

LOCAL_PACKAGE_NAME := Osu
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

include $(BUILD_PACKAGE)

########################
include $(call all-makefiles-under,$(LOCAL_PATH))
