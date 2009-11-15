LOCAL_PATH:= $(call my-dir)

#
# Build the software OpenGL ES library
#

include $(CLEAR_VARS)

# Set to 1 to use gralloc and copybits
LIBAGL_USE_GRALLOC_COPYBITS := 1

LOCAL_SRC_FILES:= \
	egl.cpp                     \
	state.cpp		            \
	texture.cpp		            \
    Tokenizer.cpp               \
    TokenManager.cpp            \
    TextureObjectManager.cpp    \
    BufferObjectManager.cpp     \
	array.cpp.arm		        \
	fp.cpp.arm		            \
	light.cpp.arm		        \
	matrix.cpp.arm		        \
	mipmap.cpp.arm		        \
	primitives.cpp.arm	        \
	vertex.cpp.arm

LOCAL_CFLAGS += -DLOG_TAG=\"libagl\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES
LOCAL_CFLAGS += -fvisibility=hidden

LOCAL_SHARED_LIBRARIES := libcutils libhardware libutils libpixelflinger
LOCAL_LDLIBS := -lpthread -ldl

ifeq ($(TARGET_ARCH),arm)
	LOCAL_SRC_FILES += fixed_asm.S iterators.S
	LOCAL_CFLAGS += -fstrict-aliasing
endif

ifneq ($(TARGET_SIMULATOR),true)
    # we need to access the private Bionic header <bionic_tls.h>
    LOCAL_C_INCLUDES += bionic/libc/private
endif

ifeq ($(LIBAGL_USE_GRALLOC_COPYBITS),1)
    LOCAL_CFLAGS += -DLIBAGL_USE_GRALLOC_COPYBITS
    LOCAL_SRC_FILES += copybit.cpp
    LOCAL_SHARED_LIBRARIES += libui
endif


LOCAL_MODULE_PATH := $(TARGET_OUT_SHARED_LIBRARIES)/egl
LOCAL_MODULE:= libGLES_android

include $(BUILD_SHARED_LIBRARY)
