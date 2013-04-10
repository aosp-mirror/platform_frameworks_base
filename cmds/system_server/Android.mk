LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	system_main.cpp

LOCAL_SHARED_LIBRARIES := \
	libutils \
	libbinder \
	libsystem_server \
	liblog

LOCAL_C_INCLUDES := \
	$(JNI_H_INCLUDE)

LOCAL_MODULE:= system_server

include $(BUILD_EXECUTABLE)

include $(LOCAL_PATH)/library/Android.mk
