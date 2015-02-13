LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := \
	$(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := android.test.runner bouncycastle conscrypt org.apache.http.legacy

LOCAL_PACKAGE_NAME := CoreTests

include $(BUILD_PACKAGE)
