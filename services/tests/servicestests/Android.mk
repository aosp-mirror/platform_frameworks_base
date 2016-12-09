#########################################################################
# Build FrameworksServicesTests package
#########################################################################

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_JAVA_LIBRARIES := \
    easymocklib \
    frameworks-base-testutils \
    services.core \
    services.devicepolicy \
    services.net \
    services.usage \
    guava \
    android-support-test \
    mockito-target \
    ShortcutManagerTestUtils

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_PACKAGE_NAME := FrameworksServicesTests

LOCAL_CERTIFICATE := platform

# These are not normally accessible from apps so they must be explicitly included.
LOCAL_JNI_SHARED_LIBRARIES := \
    libbacktrace \
    libbase \
    libbinder \
    libc++ \
    libcutils \
    liblog \
    liblzma \
    libnativehelper \
    libnetdaidl \
    libui \
    libunwind \
    libutils

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

include $(BUILD_PACKAGE)
