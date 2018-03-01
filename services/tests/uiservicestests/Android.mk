#########################################################################
# Build FrameworksUiServicesTests package
#########################################################################

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include test java files and source from notifications package.
LOCAL_SRC_FILES := $(call all-java-files-under, src) \
	$(call all-java-files-under, ../../core/java/com/android/server/notification) \
	$(call all-java-files-under, ../../core/java/com/android/server/slice) \

LOCAL_STATIC_JAVA_LIBRARIES := \
    services.accessibility \
    services.core \
    services.devicepolicy \
    services.net \
    services.usage \
    guava \
    android-support-test \
    mockito-target-inline-minus-junit4 \
    platform-test-annotations \
    testables

LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base

LOCAL_JACK_FLAGS := --multi-dex native
LOCAL_DX_FLAGS := --multi-dex

LOCAL_PACKAGE_NAME := FrameworksUiServicesTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_CERTIFICATE := platform

LOCAL_MULTILIB := both

# These are not normally accessible from apps so they must be explicitly included.
LOCAL_JNI_SHARED_LIBRARIES := \
    libdexmakerjvmtiagent \
    libmultiplejvmtiagentsinterferenceagent \
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
