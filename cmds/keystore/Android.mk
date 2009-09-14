ifneq ($(TARGET_SIMULATOR),true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    netkeystore.c netkeystore_main.c keymgmt.c

LOCAL_C_INCLUDES := \
    $(call include-path-for, system-core)/cutils \
    external/openssl/include

LOCAL_SHARED_LIBRARIES := \
    libcutils libssl

LOCAL_STATIC_LIBRARIES :=

LOCAL_MODULE:= keystore

include $(BUILD_EXECUTABLE)

endif # !simulator))
