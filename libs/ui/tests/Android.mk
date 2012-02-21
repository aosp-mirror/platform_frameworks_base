# Build the unit tests.
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Build the manual test programs.
include $(call all-makefiles-under, $(LOCAL_PATH))
