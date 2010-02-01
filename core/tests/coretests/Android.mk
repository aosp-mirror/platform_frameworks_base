LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := \
	$(call all-java-files-under, src) \
	src/android/os/IAidlTest.aidl

LOCAL_JAVA_LIBRARIES := android.test.runner
LOCAL_PACKAGE_NAME := FrameworksCoreTests

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

