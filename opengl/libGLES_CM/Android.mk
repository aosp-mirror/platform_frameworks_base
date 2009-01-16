LOCAL_PATH:= $(call my-dir)

#
# Build the wrapper OpenGL ES library
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= gl_wrapper.cpp.arm gl_logger.cpp

LOCAL_SHARED_LIBRARIES += libcutils libutils libui
LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libGLES_CM

# needed on sim build because of weird logging issues
ifeq ($(TARGET_SIMULATOR),true)
else
    LOCAL_SHARED_LIBRARIES += libdl
    # we need to access the Bionic private header <bionic_tls.h>
    LOCAL_CFLAGS += -I$(LOCAL_PATH)/../../../../bionic/libc/private
endif

include $(BUILD_SHARED_LIBRARY)

