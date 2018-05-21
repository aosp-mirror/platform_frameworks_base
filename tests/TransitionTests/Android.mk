LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

# Only compile source java files in this apk.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := TransitionTests
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_STATIC_JAVA_LIBRARIES += android-common

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

# Use the following include to make our test apk.
include $(call all-makefiles-under,$(LOCAL_PATH))
