LOCAL_PATH:= $(call my-dir)

# Build for Linux (desktop) host
ifeq ($(HOST_OS),linux)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := accessorychat.c

LOCAL_MODULE := accessorychat

LOCAL_C_INCLUDES += bionic/libc/kernel/common
LOCAL_STATIC_LIBRARIES := libusbhost libcutils
LOCAL_LDLIBS += -lpthread
LOCAL_CFLAGS := -g -O0

include $(BUILD_HOST_EXECUTABLE)

endif

# Build for device
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := accessorychat.c

LOCAL_MODULE := accessorychat

LOCAL_SHARED_LIBRARIES := libusbhost libcutils

include $(BUILD_EXECUTABLE)
