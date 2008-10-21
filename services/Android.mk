LOCAL_PATH:= $(call my-dir)

# the library
# ============================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
            $(call all-subdir-java-files)

LOCAL_MODULE:= services

LOCAL_JAVA_LIBRARIES := android.policy

include $(BUILD_JAVA_LIBRARY)

include $(BUILD_DROIDDOC)

