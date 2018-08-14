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
    frameworks-base-testutils \
    services.accessibility \
    services.appwidget \
    services.autofill \
    services.backup \
    services.core \
    services.devicepolicy \
    services.net \
    services.usage \
    guava \
    android-support-test \
    mockito-target-minus-junit4 \
    platform-test-annotations \
    ShortcutManagerTestUtils \
    truth-prebuilt \
    testables \
    testng \
    ub-uiautomator\
    platformprotosnano

LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/aidl

LOCAL_SRC_FILES += aidl/com/android/servicestests/aidl/INetworkStateObserver.aidl \
    aidl/com/android/servicestests/aidl/ICmdReceiverService.aidl
LOCAL_SRC_FILES += $(call all-java-files-under, test-apps/JobTestApp/src)
LOCAL_SRC_FILES += $(call all-java-files-under, test-apps/SuspendTestApp/src)

LOCAL_JAVA_LIBRARIES := \
    android.hidl.manager-V1.0-java \
    android.test.mock \
    android.test.base android.test.runner \

LOCAL_PACKAGE_NAME := FrameworksServicesTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

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

LOCAL_JACK_FLAGS := --multi-dex native
LOCAL_DX_FLAGS := --multi-dex

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

include $(call all-makefiles-under, $(LOCAL_PATH))
