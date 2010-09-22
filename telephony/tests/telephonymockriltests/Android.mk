LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_STATIC_JAVA_LIBRARIES := mockrilcontroller

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_PACKAGE_NAME := TelephonyMockRilTests

include $(BUILD_PACKAGE)
