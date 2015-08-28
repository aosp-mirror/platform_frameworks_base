# Copyright 2007 The Android Open Source Project
#
# Copies files into the directory structure described by a manifest

# This tool is prebuilt if we're doing an app-only build.
ifeq ($(TARGET_BUILD_APPS)$(filter true,$(TARGET_BUILD_PDK)),)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	aidl_language_l.l \
	aidl_language_y.y \
	aidl.cpp \
	aidl_language.cpp \
	options.cpp \
	search_path.cpp \
	AST.cpp \
	Type.cpp \
	generate_java.cpp \
	generate_java_binder.cpp \
	generate_java_rpc.cpp

LOCAL_CFLAGS := -g
LOCAL_MODULE := aidl

include $(BUILD_HOST_EXECUTABLE)

# Unit tests
include $(CLEAR_VARS)
LOCAL_MODULE := aidl_unittests
LOCAL_CFLAGS := -g -DUNIT_TEST
LOCAL_SRC_FILES := tests/test.cpp
LOCAL_STATIC_LIBRARIES := libgmock_host libgtest_host libBionicGtestMain
LOCAL_LDLIBS := -lrt
include $(BUILD_HOST_NATIVE_TEST)

endif # No TARGET_BUILD_APPS or TARGET_BUILD_PDK
