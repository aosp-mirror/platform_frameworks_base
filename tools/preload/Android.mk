LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := *.java

LOCAL_MODULE:= preload

include $(BUILD_HOST_JAVA_LIBRARY)

include $(call all-subdir-makefiles)
