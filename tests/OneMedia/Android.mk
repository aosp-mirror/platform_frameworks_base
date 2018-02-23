LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files) \
        $(call all-Iaidl-files-under, src)

LOCAL_PACKAGE_NAME := OneMedia
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform

LOCAL_JAVA_LIBRARIES += org.apache.http.legacy

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)
