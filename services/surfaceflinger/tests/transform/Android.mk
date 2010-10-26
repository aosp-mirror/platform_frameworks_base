LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	TransformTest.cpp \
	../../Transform.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libui \

LOCAL_MODULE:= test-transform

LOCAL_MODULE_TAGS := tests

LOCAL_C_INCLUDES += ../..

include $(BUILD_EXECUTABLE)
