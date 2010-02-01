LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_JAVA_LIBRARIES := framework-tests android.test.runner services

LOCAL_STATIC_JAVA_LIBRARIES := gsf-client

# Resource unit tests use a private locale
LOCAL_AAPT_FLAGS = -c xx_YY -c cs -c 160dpi -c 32dpi -c 240dpi

LOCAL_SRC_FILES := \
	$(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := AndroidTests
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
