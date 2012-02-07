LOCAL_PATH:= $(call my-dir)

#
# common_time_service
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    common_clock_service.cpp \
    common_time_server.cpp \
    clock_recovery.cpp \
    common_clock.cpp

ifeq ($(TIME_SERVICE_DEBUG), true)
LOCAL_SRC_FILES += diag_thread.cpp
LOCAL_CFLAGS += -DTIME_SERVICE_DEBUG
endif

LOCAL_SHARED_LIBRARIES := \
    libbinder \
    libcommon_time_client \
    libutils

LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := common_time

include $(BUILD_EXECUTABLE)
