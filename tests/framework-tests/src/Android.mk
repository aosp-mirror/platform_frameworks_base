LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_MODULE := framework-tests

LOCAL_JAVA_LIBRARIES := android.policy_phone android.test.runner

include $(BUILD_JAVA_LIBRARY)
