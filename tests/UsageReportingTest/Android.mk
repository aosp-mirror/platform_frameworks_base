LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

# Only compile source java files in this apk.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_USE_AAPT2 := true
LOCAL_STATIC_ANDROID_LIBRARIES := androidx.legacy_legacy-support-v4

LOCAL_CERTIFICATE := platform

LOCAL_PACKAGE_NAME := UsageReportingTest
LOCAL_PRIVATE_PLATFORM_APIS := true

include $(BUILD_PACKAGE)
