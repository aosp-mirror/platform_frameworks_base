LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES :=     \
        CentralTendencyStatistics.cpp \
        ThreadCpuUsage.cpp

LOCAL_MODULE := libcpustats

include $(BUILD_STATIC_LIBRARY)

#include $(CLEAR_VARS)
#
#LOCAL_SRC_FILES :=     \
#       CentralTendencyStatistics.cpp \
#       ThreadCpuUsage.cpp
#
#LOCAL_MODULE := libcpustats
#
#include $(BUILD_HOST_STATIC_LIBRARY)
