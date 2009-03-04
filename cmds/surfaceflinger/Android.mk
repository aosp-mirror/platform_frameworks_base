LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	main_surfaceflinger.cpp 

LOCAL_SHARED_LIBRARIES := \
	libsurfaceflinger \
	libutils

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/../../libs/surfaceflinger

LOCAL_MODULE:= surfaceflinger

include $(BUILD_EXECUTABLE)
