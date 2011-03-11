LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_MODULE:= com.android.nfc_extras

include $(BUILD_JAVA_LIBRARY)
