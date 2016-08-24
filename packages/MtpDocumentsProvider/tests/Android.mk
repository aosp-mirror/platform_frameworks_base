LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_JAVA_LIBRARIES := android.test.runner
LOCAL_PACKAGE_NAME := MtpDocumentsProviderTests
LOCAL_INSTRUMENTATION_FOR := MtpDocumentsProvider
LOCAL_CERTIFICATE := media

include $(BUILD_PACKAGE)
