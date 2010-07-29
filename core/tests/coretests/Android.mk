LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := \
	$(call all-java-files-under, src) \
	$(call all-Iaidl-files-under, src) \
	$(call all-java-files-under, DisabledTestApp/src) \
	$(call all-java-files-under, EnabledTestApp/src)

LOCAL_STATIC_JAVA_LIBRARIES += android-common

LOCAL_DX_FLAGS := --core-library
LOCAL_STATIC_JAVA_LIBRARIES := core-tests-supportlib
LOCAL_JAVA_LIBRARIES := android.test.runner android-common
LOCAL_PACKAGE_NAME := FrameworksCoreTests

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
