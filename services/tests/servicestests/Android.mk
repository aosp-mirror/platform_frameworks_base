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
    services.core \
    services.devicepolicy \
    services.net \
    services.usage \
    easymocklib \
    guava \
    android-support-test \
    mockito-target \
    ShortcutManagerTestUtils

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_PACKAGE_NAME := FrameworksServicesTests

LOCAL_CERTIFICATE := platform

LOCAL_JNI_SHARED_LIBRARIES := \
    libapfjni \
    libc++ \
    libnativehelper

include $(BUILD_PACKAGE)

#########################################################################
# Build JNI Shared Library
#########################################################################

LOCAL_PATH:= $(LOCAL_PATH)/jni

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS := -Wall -Werror

LOCAL_C_INCLUDES := \
  libpcap \
  hardware/google/apf

LOCAL_SRC_FILES := apf_jni.cpp

LOCAL_SHARED_LIBRARIES := \
  libnativehelper \
  liblog

LOCAL_STATIC_LIBRARIES := \
  libpcap \
  libapf

LOCAL_MODULE := libapfjni

include $(BUILD_SHARED_LIBRARY)
