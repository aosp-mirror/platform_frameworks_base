LOCAL_PATH:= $(call my-dir)

#
# common_time_service
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    common_clock_service.cpp \
    common_time_config_service.cpp \
    common_time_server.cpp \
    common_time_server_api.cpp \
    common_time_server_packets.cpp \
    clock_recovery.cpp \
    common_clock.cpp \
    main.cpp \
    utils.cpp

# Uncomment to enable vesbose logging and debug service.
#TIME_SERVICE_DEBUG=true
ifeq ($(TIME_SERVICE_DEBUG), true)
LOCAL_SRC_FILES += diag_thread.cpp
LOCAL_CFLAGS += -DTIME_SERVICE_DEBUG
endif

LOCAL_SHARED_LIBRARIES := \
    libbinder \
    libcommon_time_client \
    libutils \
    liblog

LOCAL_MODULE_TAGS := optional
LOCAL_MODULE := common_time

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_EXECUTABLE)
