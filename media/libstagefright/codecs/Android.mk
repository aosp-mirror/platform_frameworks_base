ifeq ($(BUILD_WITH_FULL_STAGEFRIGHT),true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

include $(call all-makefiles-under,$(LOCAL_PATH))

endif
