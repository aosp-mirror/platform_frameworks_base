ifneq ($(TARGET_SIMULATOR),true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    keystore.c commands.c

LOCAL_C_INCLUDES := \
    $(call include-path-for, system-core)/cutils

LOCAL_SHARED_LIBRARIES := \
    libcutils

LOCAL_STATIC_LIBRARIES :=

LOCAL_MODULE:= keystore

include $(BUILD_EXECUTABLE)

endif # !simulator))
