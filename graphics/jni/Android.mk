
# libRS needs libacc, which isn't 64-bit clean, and so can't be built
# for the simulator on gHardy, and therefore libRS needs to be excluded
# from the simulator as well, and so in turn librs_jni needs to be
# excluded.
ifneq ($(TARGET_SIMULATOR),true)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    android_renderscript_RenderScript.cpp

LOCAL_SHARED_LIBRARIES := \
        libandroid_runtime \
        libacc \
        libnativehelper \
        libRS \
        libcutils \
        libskia \
        libutils \
        libui \
        libsurfaceflinger_client 

LOCAL_STATIC_LIBRARIES :=

rs_generated_include_dir := $(call intermediates-dir-for,SHARED_LIBRARIES,libRS,,)

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	$(LOCAL_PATH)/../../libs/rs \
	$(rs_generated_include_dir) \
	$(call include-path-for, corecg graphics)

LOCAL_CFLAGS +=

LOCAL_LDLIBS := -lpthread
LOCAL_ADDITIONAL_DEPENDENCIES := $(addprefix $(rs_generated_include_dir)/,rsgApiFuncDecl.h)
LOCAL_MODULE:= librs_jni
LOCAL_ADDITIONAL_DEPENDENCIES += $(rs_generated_source)
LOCAL_MODULE_TAGS := optional
LOCAL_REQUIRED_MODULES := libRS

include $(BUILD_SHARED_LIBRARY)

endif #simulator
