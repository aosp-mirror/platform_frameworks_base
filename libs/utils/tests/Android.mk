# Build the unit tests.
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

test_src_files := \
	PollLoop_test.cpp

LOCAL_SHARED_LIBRARIES := \
	libz \
	liblog \
	libcutils \
	libutils \
	libstlport

LOCAL_STATIC_LIBRARIES := \
	libgtest \
	libgtest_main

LOCAL_C_INCLUDES := \
    external/zlib \
    external/icu4c/common \
    bionic \
    bionic/libstdc++/include \
    external/gtest/include \
    external/stlport/stlport

LOCAL_MODULE_TAGS := eng tests

$(foreach file,$(test_src_files), \
    $(eval LOCAL_SRC_FILES := $(file)) \
    $(eval LOCAL_MODULE := $(notdir $(file:%.cpp=%))) \
    $(eval include $(BUILD_EXECUTABLE)) \
)
