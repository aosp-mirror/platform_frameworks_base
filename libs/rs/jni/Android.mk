LOCAL_PATH:=$(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	RenderScript_jni.cpp

LOCAL_SHARED_LIBRARIES := \
        libandroid_runtime \
        libacc \
        libnativehelper \
        libRS \
        libcutils \
        libsgl \
        libutils \
        libui

LOCAL_STATIC_LIBRARIES :=

rs_generated_include_dir := $(call intermediates-dir-for,SHARED_LIBRARIES,libRS,,)

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	$(rs_generated_include_dir) \
	$(call include-path-for, corecg graphics)

LOCAL_CFLAGS +=

LOCAL_LDLIBS := -lpthread

LOCAL_MODULE:= libRS_jni
LOCAL_PRELINK_MODULE := false

LOCAL_ADDITIONAL_DEPENDENCIES += $(rs_generated_source)

include $(BUILD_SHARED_LIBRARY)

