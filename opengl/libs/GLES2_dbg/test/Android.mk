LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_C_INCLUDES := \
    $(LOCAL_PATH) \
    $(LOCAL_PATH)/../src \
    $(LOCAL_PATH)/../../ \
    external/gtest/include \
    external/stlport/stlport \
    external/protobuf/src \
    bionic \
    external \
#

LOCAL_SRC_FILES:= \
    test_main.cpp \
    test_server.cpp \
    test_socket.cpp \
#

LOCAL_SHARED_LIBRARIES := libcutils libutils libGLESv2_dbg libstlport
LOCAL_STATIC_LIBRARIES := libgtest libprotobuf-cpp-2.3.0-lite liblzf
LOCAL_MODULE_TAGS := tests
LOCAL_MODULE:= libGLESv2_dbg_test

ifeq ($(ARCH_ARM_HAVE_TLS_REGISTER),true)
    LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
endif
LOCAL_C_INCLUDES += bionic/libc/private

LOCAL_CFLAGS += -DLOG_TAG=\"libEGL\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES
LOCAL_CFLAGS += -fvisibility=hidden

include $(BUILD_EXECUTABLE)

