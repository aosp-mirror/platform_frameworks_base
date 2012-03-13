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

intermediates := $(call intermediates-dir-for,STATIC_LIBRARIES,libRS,TARGET,)
librs_generated_headers := \
    $(intermediates)/rsgApiStructs.h \
    $(intermediates)/rsgApiFuncDecl.h
LOCAL_GENERATED_SOURCES := $(librs_generated_headers)

LOCAL_C_INCLUDES += frameworks/base/libs/rs
LOCAL_C_INCLUDES += $(intermediates)


include $(BUILD_EXECUTABLE)

