#########################################################################
# Build FrameworksNetTests package
#########################################################################

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, java)

LOCAL_STATIC_JAVA_LIBRARIES := \
    frameworks-base-testutils \
    framework-protos \
    android-support-test \
    mockito-target-minus-junit4 \
    platform-test-annotations \
    services.core \
    services.ipmemorystore \
    services.net

LOCAL_JAVA_LIBRARIES := \
    android.test.runner \
    android.test.base \
    android.test.mock

LOCAL_PACKAGE_NAME := FrameworksNetTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_CERTIFICATE := platform

# These are not normally accessible from apps so they must be explicitly included.
LOCAL_JNI_SHARED_LIBRARIES := \
    android.hidl.token@1.0 \
    libartbase \
    libbacktrace \
    libbase \
    libbinder \
    libbinderthreadstate \
    libc++ \
    libcrypto \
    libcutils \
    libdexfile \
    libframeworksnettestsjni \
    libhidl-gen-utils \
    libhidlbase \
    libhidltransport \
    libhwbinder \
    liblog \
    liblzma \
    libnativehelper \
    libpackagelistparser \
    libpcre2 \
    libprocessgroup \
    libselinux \
    libui \
    libutils \
    libvintf \
    libvndksupport \
    libtinyxml2 \
    libunwindstack \
    libutilscallstack \
    libziparchive \
    libz \
    netd_aidl_interface-cpp

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

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
  liblog \
  libcutils \
  libnativehelper \
  netd_aidl_interface-cpp

LOCAL_STATIC_LIBRARIES := \
  libpcap \
  libapf

LOCAL_MODULE := libframeworksnettestsjni

include $(BUILD_SHARED_LIBRARY)
