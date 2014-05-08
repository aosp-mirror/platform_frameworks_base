LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    android_renderscript_RenderScript.cpp

LOCAL_SHARED_LIBRARIES := \
        libandroid_runtime \
        libandroidfw \
        libnativehelper \
        libRS \
        libcutils \
        liblog \
        libskia \
        libutils \
        libui \
        libgui

LOCAL_STATIC_LIBRARIES :=

rs_generated_include_dir := $(call intermediates-dir-for,SHARED_LIBRARIES,libRS,,)

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	frameworks/rs \
	$(rs_generated_include_dir)

LOCAL_CFLAGS += -Wno-unused-parameter

LOCAL_ADDITIONAL_DEPENDENCIES := $(addprefix $(rs_generated_include_dir)/,rsgApiFuncDecl.h)
LOCAL_MODULE:= librs_jni
LOCAL_ADDITIONAL_DEPENDENCIES += $(rs_generated_source)
LOCAL_MODULE_TAGS := optional
LOCAL_REQUIRED_MODULES := libRS libRSDriver

include $(BUILD_SHARED_LIBRARY)
