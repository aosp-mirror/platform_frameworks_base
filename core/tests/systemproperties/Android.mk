LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := \
	$(call all-java-files-under, src)

LOCAL_DX_FLAGS := --core-library
LOCAL_STATIC_JAVA_LIBRARIES := core-tests android-common frameworks-core-util-lib
LOCAL_JAVA_LIBRARIES := android.test.runner
LOCAL_PACKAGE_NAME := FrameworksCoreSystemPropertiesTests

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
