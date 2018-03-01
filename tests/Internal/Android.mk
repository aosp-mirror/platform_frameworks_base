LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true
LOCAL_MODULE_TAGS := tests

LOCAL_PROTOC_OPTIMIZE_TYPE := nano

# Include some source files directly to be able to access package members
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.test.runner
LOCAL_STATIC_JAVA_LIBRARIES := junit \
    android-support-test \
    mockito-target-minus-junit4

LOCAL_JAVA_RESOURCE_DIRS := res
LOCAL_CERTIFICATE := platform

LOCAL_PACKAGE_NAME := InternalTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
