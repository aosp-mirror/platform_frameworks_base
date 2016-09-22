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
    services.core \
    services.devicepolicy \
    services.net \
    services.usage \
    easymocklib \
    guava \
    android-support-test \
    mockito-target \
    ShortcutManagerTestUtils \
    truth-prebuilt

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_PACKAGE_NAME := FrameworksServicesTests

LOCAL_CERTIFICATE := platform

# These are not normally accessible from apps so they must be explicitly included.
LOCAL_JNI_SHARED_LIBRARIES := libservicestestsjni \
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

# Code coverage puts us over the dex limit, so enable multi-dex for coverage-enabled builds
ifeq (true,$(EMMA_INSTRUMENT))
LOCAL_JACK_FLAGS := --multi-dex native
endif # EMMA_INSTRUMENT_STATIC

include $(BUILD_PACKAGE)

#########################################################################
# Build JNI Shared Library
#########################################################################

LOCAL_PATH:= $(LOCAL_PATH)/jni

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS := -Wall -Wextra -Werror

LOCAL_C_INCLUDES := \
  libpcap \
  hardware/google/apf

LOCAL_SRC_FILES := $(call all-cpp-files-under)

LOCAL_SHARED_LIBRARIES := \
  libbinder \
  libcutils \
  libnativehelper \
  libnetdaidl

LOCAL_STATIC_LIBRARIES := \
  libpcap \
  libapf

LOCAL_MODULE := libservicestestsjni

include $(BUILD_SHARED_LIBRARY)
