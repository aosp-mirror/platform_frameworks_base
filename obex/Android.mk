LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_MODULE:= javax.obex

include $(BUILD_JAVA_LIBRARY)


include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_MODULE:= javax.obexstatic

include $(BUILD_STATIC_JAVA_LIBRARY)