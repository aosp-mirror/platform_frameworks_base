LOCAL_PATH:= $(call my-dir)

# the library
# ============================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
            $(call all-subdir-java-files)
            
LOCAL_MODULE := android.policy

include $(BUILD_JAVA_LIBRARY)
