LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := HierarchyViewerTest

LOCAL_JAVA_LIBRARIES := android.test.runner
LOCAL_STATIC_JAVA_LIBRARIES := junit legacy-android-test

include $(BUILD_PACKAGE)
