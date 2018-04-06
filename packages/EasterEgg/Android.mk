LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_STATIC_JAVA_LIBRARIES := \
    jsr305

LOCAL_STATIC_ANDROID_LIBRARIES := \
    androidx.legacy_legacy-support-v4 \
    androidx.legacy_legacy-support-v13 \
    androidx.dynamicanimation_dynamicanimation \
    androidx.recyclerview_recyclerview \
    androidx.preference_preference \
    androidx.appcompat_appcompat \
    androidx.legacy_legacy-preference-v14

LOCAL_USE_AAPT2 := true

LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := EasterEgg
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
