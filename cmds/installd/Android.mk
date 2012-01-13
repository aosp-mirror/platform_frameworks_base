LOCAL_PATH := $(call my-dir)

common_src_files := \
    commands.c utils.c

#
# Static library used in testing and executable
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    $(common_src_files)

LOCAL_MODULE := libinstalld

LOCAL_MODULE_TAGS := eng tests

include $(BUILD_STATIC_LIBRARY)

#
# Executable
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    installd.c \
    $(common_src_files)

LOCAL_SHARED_LIBRARIES := \
    libcutils

LOCAL_STATIC_LIBRARIES := \
    libdiskusage

ifeq ($(HAVE_SELINUX),true)
LOCAL_C_INCLUDES += external/libselinux/include
LOCAL_SHARED_LIBRARIES += libselinux
LOCAL_CFLAGS := -DHAVE_SELINUX
endif # HAVE_SELINUX

LOCAL_MODULE := installd

LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)
