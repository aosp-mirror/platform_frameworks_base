LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_JAVA_LIBRARIES := framework-tests android.test.runner

LOCAL_STATIC_JAVA_LIBRARIES := googlelogin-client

# Resource unit tests use a private locale
LOCAL_AAPT_FLAGS = -c xx_YY -c cs

LOCAL_SRC_FILES := \
	$(call all-subdir-java-files) \
	src/com/android/unit_tests/os/IAidlTest.aidl

LOCAL_PACKAGE_NAME := AndroidTests
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
