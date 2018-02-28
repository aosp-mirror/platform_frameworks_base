LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := \
	$(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base
LOCAL_PACKAGE_NAME := NotificationStressTests
# Could build against SDK if it wasn't for the @RepetitiveTest annotation.
LOCAL_PRIVATE_PLATFORM_APIS := true


LOCAL_STATIC_JAVA_LIBRARIES := \
    junit \
    ub-uiautomator

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
