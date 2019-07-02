LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_STATIC_JAVA_LIBRARIES := androidx.test.rules
LOCAL_PACKAGE_NAME := MtpDocumentsProviderPerfTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_INSTRUMENTATION_FOR := MtpDocumentsProvider
LOCAL_CERTIFICATE := media
LOCAL_COMPATIBILITY_SUITE += device-tests

include $(BUILD_PACKAGE)
