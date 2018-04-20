LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_USE_AAPT2 := true
LOCAL_STATIC_ANDROID_LIBRARIES := androidx.legacy_legacy-support-v4
LOCAL_STATIC_JAVA_LIBRARIES := services.net

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := CaptivePortalLogin
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
