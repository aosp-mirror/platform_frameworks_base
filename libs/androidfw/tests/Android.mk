# Build the unit tests.
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# Build the unit tests.
test_src_files := \
    InputChannel_test.cpp \
    InputEvent_test.cpp \
    InputPublisherAndConsumer_test.cpp \
    ObbFile_test.cpp

shared_libraries := \
    libandroidfw \
    libcutils \
    libutils \
    libbinder \
    libui \
    libstlport \
    libskia

static_libraries := \
    libgtest \
    libgtest_main

$(foreach file,$(test_src_files), \
    $(eval include $(CLEAR_VARS)) \
    $(eval LOCAL_SHARED_LIBRARIES := $(shared_libraries)) \
    $(eval LOCAL_STATIC_LIBRARIES := $(static_libraries)) \
    $(eval LOCAL_SRC_FILES := $(file)) \
    $(eval LOCAL_MODULE := $(notdir $(file:%.cpp=%))) \
    $(eval include $(BUILD_NATIVE_TEST)) \
)

# Build the manual test programs.
include $(call all-makefiles-under, $(LOCAL_PATH))
