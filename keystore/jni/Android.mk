LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    cert.c certtool.c

LOCAL_C_INCLUDES += \
  $(JNI_H_INCLUDE) \
  external/openssl/include

LOCAL_SHARED_LIBRARIES := \
  libcutils \
  libnativehelper \
  libutils \
  libcrypto

ifeq ($(TARGET_SIMULATOR),true)
ifeq ($(TARGET_OS),linux)
ifeq ($(TARGET_ARCH),x86)
LOCAL_LDLIBS += -lpthread -ldl -lrt -lssl
endif
endif
endif

ifeq ($(WITH_MALLOC_LEAK_CHECK),true)
  LOCAL_CFLAGS += -DMALLOC_LEAK_CHECK
endif

LOCAL_MODULE:= libcerttool_jni

include $(BUILD_SHARED_LIBRARY)
