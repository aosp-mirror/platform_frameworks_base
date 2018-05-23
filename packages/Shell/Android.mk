LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_SRC_FILES += \
        ../../../native/cmds/dumpstate/binder/android/os/IDumpstate.aidl \
        ../../../native/cmds/dumpstate/binder/android/os/IDumpstateListener.aidl \
        ../../../native/cmds/dumpstate/binder/android/os/IDumpstateToken.aidl

LOCAL_AIDL_INCLUDES = frameworks/native/cmds/dumpstate/binder

LOCAL_STATIC_ANDROID_LIBRARIES := androidx.legacy_legacy-support-v4
LOCAL_USE_AAPT2 := true

LOCAL_PACKAGE_NAME := Shell
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

LOCAL_JACK_COVERAGE_INCLUDE_FILTER := com.android.shell.*

include $(BUILD_PACKAGE)

include $(LOCAL_PATH)/tests/Android.mk
