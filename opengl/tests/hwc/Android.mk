LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES:= hwc_stress.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libEGL \
    libGLESv2 \
    libui \
    libhardware \

LOCAL_STATIC_LIBRARIES := \
    libtestUtil \

LOCAL_C_INCLUDES += \
    system/extras/tests/include \
    hardware/libhardware/include \

LOCAL_MODULE:= hwc_stress
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/nativestresstest

LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS := -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

include $(BUILD_NATIVE_TEST)
