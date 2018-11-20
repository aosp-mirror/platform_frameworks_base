#########################################################################
# Build WmTests package
#########################################################################

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := \
    $(call all-java-files-under, src) \
    $(call all-java-files-under, ../servicestests/utils) \

LOCAL_STATIC_JAVA_LIBRARIES := \
    frameworks-base-testutils \
    services.core \
    androidx.test.runner \
    androidx.test.rules \
    mockito-target-minus-junit4 \
    platform-test-annotations \
    truth-prebuilt \
    testables \
    ub-uiautomator \
    hamcrest-library

LOCAL_JAVA_LIBRARIES := \
    android.test.mock \
    android.test.base \
    android.test.runner \

LOCAL_PACKAGE_NAME := WmTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_CERTIFICATE := platform

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

LOCAL_JACK_FLAGS := --multi-dex native
LOCAL_DX_FLAGS := --multi-dex

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

include $(call all-makefiles-under, $(LOCAL_PATH))
