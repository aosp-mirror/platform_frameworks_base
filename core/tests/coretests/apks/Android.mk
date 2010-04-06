LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# build sub packages
include $(call all-makefiles-under,$(LOCAL_PATH))
