LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    src/DebuggerMessage.pb.cpp \
    src/api.cpp \
    src/server.cpp \
    src/shader.cpp \
    src/texture.cpp

LOCAL_C_INCLUDES :=	\
    $(LOCAL_PATH) \
    $(LOCAL_PATH)/../ \
    external/stlport/stlport \
    external/protobuf/src \
    bionic

LOCAL_SHARED_LIBRARIES := libstlport libcutils libutils
LOCAL_LDLIBS := -lpthread

#LOCAL_CFLAGS += -O0 -g -DDEBUG -UNDEBUG
LOCAL_CFLAGS := -DGOOGLE_PROTOBUF_NO_RTTI

ifeq ($(TARGET_ARCH),arm)
	LOCAL_CFLAGS += -fstrict-aliasing
endif

ifeq ($(ARCH_ARM_HAVE_TLS_REGISTER),true)
    LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
endif

ifneq ($(TARGET_SIMULATOR),true)
    # we need to access the private Bionic header <bionic_tls.h>
    # on ARM platforms, we need to mirror the ARCH_ARM_HAVE_TLS_REGISTER
    # behavior from the bionic Android.mk file
    ifeq ($(TARGET_ARCH)-$(ARCH_ARM_HAVE_TLS_REGISTER),arm-true)
        LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
    endif
    LOCAL_C_INCLUDES += bionic/libc/private
endif

LOCAL_MODULE:= libGLESv2_dbg
LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)
