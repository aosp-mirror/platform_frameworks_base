# Build the unit tests.
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

ifneq ($(TARGET_SIMULATOR),true)

LOCAL_MODULE := SurfaceEncoder_test

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := \
    SurfaceEncoder_test.cpp \
	DummyRecorder.cpp \

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
	libstagefright \
	libstagefright_omx \
	libstagefright_foundation \

LOCAL_STATIC_LIBRARIES := \
	libgtest \
	libgtest_main \

LOCAL_C_INCLUDES := \
    bionic \
    bionic/libstdc++/include \
    external/gtest/include \
    external/stlport/stlport \
	frameworks/base/media/libstagefright \
	frameworks/base/media/libstagefright/include \
	$(TOP)/frameworks/base/include/media/stagefright/openmax \

include $(BUILD_EXECUTABLE)

endif

# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif
