LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := eng tests

LOCAL_SRC_FILES := \
	$(call all-subdir-java-files)
LOCAL_SRC_FILES += \
	$(call all-java-files-under, ../com)

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_PACKAGE_NAME := CoreTests

include $(BUILD_PACKAGE)
