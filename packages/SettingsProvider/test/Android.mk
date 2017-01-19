LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

# Note we statically link SettingsState to do some unit tests.  It's not accessible otherwise
# because this test is not an instrumentation test. (because the target runs in the system process.)
LOCAL_SRC_FILES := $(call all-subdir-java-files) \
    ../src/com/android/providers/settings/SettingsState.java

LOCAL_STATIC_JAVA_LIBRARIES := android-support-test legacy-android-test

LOCAL_PACKAGE_NAME := SettingsProviderTest

LOCAL_MODULE_TAGS := tests

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
