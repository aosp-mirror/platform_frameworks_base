LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	compute.cpp \
	ScriptC_mono.cpp

LOCAL_SHARED_LIBRARIES := \
	libRS \
	libz \
	libcutils \
	libutils \
	libEGL \
	libGLESv1_CM \
	libGLESv2 \
	libui \
	libbcc \
	libbcinfo \
	libgui

LOCAL_MODULE:= rstest-compute

LOCAL_MODULE_TAGS := tests

LOCAL_C_INCLUDES +=  .. \
	frameworks/base/libs/rs \
	out/target/product/stingray/obj/SHARED_LIBRARIES/libRS_intermediates	

include $(BUILD_EXECUTABLE)

