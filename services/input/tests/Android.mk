# Build the unit tests.
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Build the unit tests.
test_src_files := \
    InputReader_test.cpp \
    InputDispatcher_test.cpp

shared_libraries := \
    libcutils \
    libutils \
    libhardware \
    libhardware_legacy \
    libui \
    libskia \
    libstlport \
    libinput

static_libraries := \
    libgtest \
    libgtest_main

c_includes := \
    bionic \
    bionic/libstdc++/include \
    external/gtest/include \
    external/stlport/stlport \
    external/skia/include/core

module_tags := eng tests

$(foreach file,$(test_src_files), \
    $(eval include $(CLEAR_VARS)) \
    $(eval LOCAL_SHARED_LIBRARIES := $(shared_libraries)) \
    $(eval LOCAL_STATIC_LIBRARIES := $(static_libraries)) \
    $(eval LOCAL_C_INCLUDES := $(c_includes)) \
    $(eval LOCAL_SRC_FILES := $(file)) \
    $(eval LOCAL_MODULE := $(notdir $(file:%.cpp=%))) \
    $(eval LOCAL_MODULE_TAGS := $(module_tags)) \
    $(eval include $(BUILD_EXECUTABLE)) \
)

# Build the manual test programs.
include $(call all-makefiles-under, $(LOCAL_PATH))
