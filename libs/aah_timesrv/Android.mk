LOCAL_PATH:= $(call my-dir)
#
# libaah_timesrv_client
# (binder marshalers for ICommonClock as well as common clock and local clock
# helper code)
#

include $(CLEAR_VARS)

LOCAL_MODULE := libaah_timesrv_client
LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := cc_helper.cpp \
                   local_clock.cpp \
                   ICommonClock.cpp
LOCAL_SHARED_LIBRARIES := libbinder \
                          libhardware \
                          libutils

include $(BUILD_SHARED_LIBRARY)
