LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := install_jni_lib_open_from_apk

LOCAL_JNI_SHARED_LIBRARIES_ZIP_OPTIONS := -0
LOCAL_PAGE_ALIGN_JNI_SHARED_LIBRARIES := true

include $(FrameworkCoreTests_BUILD_PACKAGE)
