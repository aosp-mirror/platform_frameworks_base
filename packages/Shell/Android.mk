LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_JAVA_LIBRARIES := android-support-v4 \
        android-support-documents-archive

LOCAL_PACKAGE_NAME := Shell
LOCAL_CERTIFICATE := platform
LOCAL_PRIVILEGED_MODULE := true

LOCAL_JACK_COVERAGE_INCLUDE_FILTER := com.android.shell.*

include $(BUILD_PACKAGE)

include $(LOCAL_PATH)/tests/Android.mk
