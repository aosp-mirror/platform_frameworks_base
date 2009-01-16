ifeq ($(TARGET_SIMULATOR),true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	ServiceManager.cpp \
	SignalHandler.cpp \
	main_runtime.cpp 

LOCAL_SHARED_LIBRARIES := \
	libutils \
	libandroid_runtime \
	libcutils \
	libui \
	libsystem_server \
	libhardware_legacy

LOCAL_C_INCLUDES := \
	$(JNI_H_INCLUDE)

ifeq ($(TARGET_OS),linux)
	LOCAL_CFLAGS += -DXP_UNIX
endif

LOCAL_MODULE:= runtime

include $(BUILD_EXECUTABLE)
endif
