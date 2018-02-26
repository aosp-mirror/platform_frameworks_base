LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := \
	$(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := \
    android.test.runner.stubs \
    org.apache.http.legacy \
    android.test.base.stubs \

LOCAL_SDK_VERSION := current

LOCAL_STATIC_JAVA_LIBRARIES := junit

LOCAL_PACKAGE_NAME := LegacyCoreTests

include $(BUILD_PACKAGE)
