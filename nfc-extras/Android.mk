LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, java)

LOCAL_MODULE:= com.android.nfc_extras

include $(BUILD_JAVA_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
