LOCAL_PATH:= $(call my-dir)

#
# aah_timesrv
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    aah_common_clock_service.cpp \
    aah_timesrv.cpp \
    clock_recovery.cpp \
    common_clock.cpp

ifeq ($(AAH_TSDEBUG), true)
LOCAL_SRC_FILES += diag_thread.cpp
LOCAL_CFLAGS += -DAAH_TSDEBUG
endif

LOCAL_SHARED_LIBRARIES := \
    libaah_timesrv_client \
    libbinder \
    libutils

LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := aah_timesrv

include $(BUILD_EXECUTABLE)
