LOCAL_PATH:= $(call my-dir)

# the library
# ============================================================
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
            $(call all-subdir-java-files)
            
LOCAL_STATIC_JAVA_LIBRARIES += android-common

LOCAL_MODULE := android.policy_phone
LOCAL_UNINSTALLABLE_MODULE := true

include $(BUILD_JAVA_LIBRARY)
