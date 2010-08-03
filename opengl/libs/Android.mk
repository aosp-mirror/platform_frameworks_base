LOCAL_PATH:= $(call my-dir)

###############################################################################
# Build META EGL library
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= 	       \
	EGL/egl.cpp 	       \
	EGL/getProcAddress.cpp.arm \
	EGL/hooks.cpp 	       \
	EGL/Loader.cpp 	       \
#

LOCAL_SHARED_LIBRARIES += libcutils libutils
LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libEGL

# needed on sim build because of weird logging issues
ifeq ($(TARGET_SIMULATOR),true)
else
    LOCAL_SHARED_LIBRARIES += libdl
    # Bionic's private TLS header relies on the ARCH_ARM_HAVE_TLS_REGISTER to
    # select the appropriate TLS codepath
    ifeq ($(ARCH_ARM_HAVE_TLS_REGISTER),true)
        LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
    endif
    # we need to access the private Bionic header <bionic_tls.h>
    LOCAL_C_INCLUDES += bionic/libc/private
endif

LOCAL_CFLAGS += -DLOG_TAG=\"libEGL\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES
LOCAL_CFLAGS += -fvisibility=hidden

ifeq ($(TARGET_BOARD_PLATFORM),msm7k)
LOCAL_CFLAGS += -DADRENO130=1
endif

ifeq ($(ARCH_ARM_HAVE_TLS_REGISTER),true)
  LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
endif

include $(BUILD_SHARED_LIBRARY)
installed_libEGL := $(LOCAL_INSTALLED_MODULE)


# OpenGL drivers config file
ifneq ($(BOARD_EGL_CFG),)

include $(CLEAR_VARS)
LOCAL_MODULE := egl.cfg
LOCAL_MODULE_TAGS := optional
LOCAL_MODULE_CLASS := ETC
LOCAL_MODULE_PATH := $(TARGET_OUT)/lib/egl
LOCAL_SRC_FILES := ../../../../$(BOARD_EGL_CFG)
include $(BUILD_PREBUILT)

# make sure we depend on egl.cfg, so it gets installed
$(installed_libEGL): | egl.cfg

endif

###############################################################################
# Build the wrapper OpenGL ES 1.x library
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= 		\
	GLES_CM/gl.cpp.arm 	\
#

LOCAL_SHARED_LIBRARIES += libcutils libEGL
LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libGLESv1_CM

# needed on sim build because of weird logging issues
ifeq ($(TARGET_SIMULATOR),true)
else
    LOCAL_SHARED_LIBRARIES += libdl
    # we need to access the private Bionic header <bionic_tls.h>
    ifeq ($(ARCH_ARM_HAVE_TLS_REGISTER),true)
        LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
    endif
    LOCAL_C_INCLUDES += bionic/libc/private
endif

LOCAL_CFLAGS += -DLOG_TAG=\"libGLESv1\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES
LOCAL_CFLAGS += -fvisibility=hidden

ifeq ($(ARCH_ARM_HAVE_TLS_REGISTER),true)
  LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
endif

include $(BUILD_SHARED_LIBRARY)


###############################################################################
# Build the wrapper OpenGL ES 2.x library
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= 		\
	GLES2/gl2.cpp.arm 	\
#

LOCAL_SHARED_LIBRARIES += libcutils libEGL
LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libGLESv2

# needed on sim build because of weird logging issues
ifeq ($(TARGET_SIMULATOR),true)
else
    LOCAL_SHARED_LIBRARIES += libdl
    # we need to access the private Bionic header <bionic_tls.h>
    ifeq ($(ARCH_ARM_HAVE_TLS_REGISTER),true)
        LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
    endif
    LOCAL_C_INCLUDES += bionic/libc/private
endif

LOCAL_CFLAGS += -DLOG_TAG=\"libGLESv2\"
LOCAL_CFLAGS += -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES
LOCAL_CFLAGS += -fvisibility=hidden

ifeq ($(ARCH_ARM_HAVE_TLS_REGISTER),true)
  LOCAL_CFLAGS += -DHAVE_ARM_TLS_REGISTER
endif

include $(BUILD_SHARED_LIBRARY)

###############################################################################
# Build the ETC1 host static library
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= 		\
	ETC1/etc1.cpp 	\
#

LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libETC1

include $(BUILD_HOST_STATIC_LIBRARY)

###############################################################################
# Build the ETC1 device library
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= 		\
	ETC1/etc1.cpp 	\
#

LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libETC1

include $(BUILD_SHARED_LIBRARY)
