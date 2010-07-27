LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	mtp.cpp \
	MtpFile.cpp \

LOCAL_C_INCLUDES += \
    frameworks/base/media/mtp \

LOCAL_CFLAGS := -DMTP_HOST

LOCAL_MODULE := mtp

LOCAL_STATIC_LIBRARIES := libmtp libusbhost libutils libcutils

include $(BUILD_EXECUTABLE)

ifeq ($(HOST_OS),linux)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	mtp.cpp \
	MtpFile.cpp \
	../../../libs/utils/RefBase.cpp \
	../../../libs/utils/SharedBuffer.cpp \
	../../../libs/utils/Threads.cpp \
	../../../libs/utils/VectorImpl.cpp \

LOCAL_C_INCLUDES += \
    frameworks/base/media/mtp \

LOCAL_CFLAGS := -DMTP_HOST -g -O0

have_readline := $(wildcard /usr/include/readline/readline.h)
have_history := $(wildcard /usr/lib/libhistory*)
ifneq ($(strip $(have_readline)),)
LOCAL_CFLAGS += -DHAVE_READLINE=1
endif

LOCAL_LDLIBS += -lpthread
ifneq ($(strip $(have_readline)),)
LOCAL_LDLIBS += -lreadline -lncurses
endif
ifneq ($(strip $(have_history)),)
LOCAL_LDLIBS += -lhistory
endif

LOCAL_MODULE := mtp

LOCAL_STATIC_LIBRARIES := libmtp libusbhost libcutils

include $(BUILD_HOST_EXECUTABLE)

endif
