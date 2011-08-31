# Build the unit tests,
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := SurfaceTexture_test

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := \
    Surface_test.cpp \
    SurfaceTextureClient_test.cpp \
    SurfaceTexture_test.cpp \

LOCAL_SHARED_LIBRARIES := \
	libEGL \
	libGLESv2 \
	libandroid \
	libbinder \
	libcutils \
	libgui \
	libstlport \
	libui \
	libutils \

LOCAL_C_INCLUDES := \
    bionic \
    bionic/libstdc++/include \
    external/gtest/include \
    external/stlport/stlport \

# Build the binary to $(TARGET_OUT_DATA_NATIVE_TESTS)/$(LOCAL_MODULE)
# to integrate with auto-test framework.
include $(BUILD_NATIVE_TEST)

# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif
