LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

# Only compile source java files in this apk.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := RcsTests
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_CERTIFICATE := platform
LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base
LOCAL_STATIC_JAVA_LIBRARIES := junit android-support-test mockito-target-minus-junit4 truth-prebuilt

include $(BUILD_PACKAGE)

# Use the following include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))
