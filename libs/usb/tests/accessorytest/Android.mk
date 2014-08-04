LOCAL_PATH:= $(call my-dir)

# Build for Linux host only
ifeq ($(HOST_OS),linux)

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES :=  accessory.c \
                    audio.c     \
                    hid.c       \
                    usb.c

LOCAL_C_INCLUDES += external/tinyalsa/include

LOCAL_MODULE := accessorytest

LOCAL_STATIC_LIBRARIES := libusbhost libcutils libtinyalsa
LOCAL_LDLIBS += -lpthread
LOCAL_CFLAGS := -g -O0

include $(BUILD_HOST_EXECUTABLE)

endif
