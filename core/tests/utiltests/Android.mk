#########################################################################
# Build FrameworksUtilTests package
#########################################################################

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_SRC_FILES += src/android/util/IRemoteMemoryIntArray.aidl

LOCAL_JNI_SHARED_LIBRARIES := libmemoryintarraytest libcutils libc++

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.test.rules \
    frameworks-base-testutils \
    mockito-target-minus-junit4 \

LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base android.test.mock

LOCAL_PACKAGE_NAME := FrameworksUtilTests
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_CERTIFICATE := platform

LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)

