
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
#LOCAL_SDK_VERSION := current

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.test.runner
LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4 mockito-target guava ub-uiautomator

LOCAL_PACKAGE_NAME := DocumentsUITests
LOCAL_INSTRUMENTATION_FOR := DocumentsUI

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

include $(LOCAL_PATH)/../testing/TestDocumentsProvider/Android.mk
