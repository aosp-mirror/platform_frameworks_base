ifeq ($(TARGET_ARCH),arm)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	sdutil.cpp \

LOCAL_SHARED_LIBRARIES := libhardware_legacy libcutils libutils libc

LOCAL_MODULE:= sdutil

include $(BUILD_EXECUTABLE)

endif
