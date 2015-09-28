BASE_PATH := $(call my-dir)
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

# setup for skia optimizations
#
ifneq ($(ARCH_ARM_HAVE_VFP),true)
    LOCAL_CFLAGS += -DSK_SOFTWARE_FLOAT
endif

ifeq ($(ARCH_ARM_HAVE_NEON),true)
    LOCAL_CFLAGS += -D__ARM_HAVE_NEON
endif

# our source files
#
LOCAL_SRC_FILES:= \
    bitmap.cpp

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libskia

LOCAL_C_INCLUDES += \
    frameworks/base/native/include \
    frameworks/base/core/jni/android/graphics \
    frameworks/base/libs/hwui

LOCAL_MODULE:= libjnigraphics

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

# TODO: This is to work around b/24465209. Remove after root cause is fixed
LOCAL_LDFLAGS_arm := -Wl,--hash-style=both

include $(BUILD_SHARED_LIBRARY)

