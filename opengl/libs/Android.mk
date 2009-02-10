LOCAL_PATH:= $(call my-dir)

#
# Build META EGL library
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= 	\
	EGL/egl.cpp 		\
	EGL/gpu.cpp			\
#

LOCAL_SHARED_LIBRARIES += libcutils libutils libui
LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libEGL

# needed on sim build because of weird logging issues
ifeq ($(TARGET_SIMULATOR),true)
else
    LOCAL_SHARED_LIBRARIES += libdl
    # we need to access the Bionic private header <bionic_tls.h>
    LOCAL_CFLAGS += -I$(LOCAL_PATH)/../../../../bionic/libc/private
endif

include $(BUILD_SHARED_LIBRARY)



#
# Build the wrapper OpenGL ES library
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= 	\
	GLES_CM/gl.cpp.arm 		\
	GLES_CM/gl_logger.cpp 	\
#

LOCAL_SHARED_LIBRARIES += libcutils libutils libui libEGL
LOCAL_LDLIBS := -lpthread -ldl
LOCAL_MODULE:= libGLESv1_CM

# needed on sim build because of weird logging issues
ifeq ($(TARGET_SIMULATOR),true)
else
    LOCAL_SHARED_LIBRARIES += libdl
    # we need to access the Bionic private header <bionic_tls.h>
    LOCAL_CFLAGS += -I$(LOCAL_PATH)/../../../../bionic/libc/private
endif

include $(BUILD_SHARED_LIBRARY)
