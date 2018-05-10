LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

# Note we statically link several classes to do some unit tests.  It's not accessible otherwise
# because this test is not an instrumentation test. (because the target runs in the system process.)
LOCAL_SRC_FILES := $(call all-subdir-java-files) \
    ../src/com/android/providers/settings/SettingsState.java \
    ../src/com/android/providers/settings/SettingsHelper.java

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-test \
    truth-prebuilt

LOCAL_JAVA_LIBRARIES := android.test.base

LOCAL_PACKAGE_NAME := SettingsProviderTest
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_MODULE_TAGS := tests

LOCAL_CERTIFICATE := platform

LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
