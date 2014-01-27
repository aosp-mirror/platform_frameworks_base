# 
# Copyright 2006 The Android Open Source Project
#
# Android Asset Packaging Tool
#

# This tool is prebuilt if we're doing an app-only build.
ifeq ($(TARGET_BUILD_APPS),)


aapt_src_files := \
	AaptAssets.cpp \
	Command.cpp \
	CrunchCache.cpp \
	FileFinder.cpp \
	Main.cpp \
	Package.cpp \
	StringPool.cpp \
	XMLNode.cpp \
	ResourceFilter.cpp \
	ResourceIdCache.cpp \
	ResourceTable.cpp \
	Images.cpp \
	Resource.cpp \
    pseudolocalize.cpp \
    SourcePos.cpp \
	WorkQueue.cpp \
    ZipEntry.cpp \
    ZipFile.cpp \
	qsort_r_compat.c

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(aapt_src_files)

LOCAL_CFLAGS += -Wno-format-y2k
ifeq (darwin,$(HOST_OS))
LOCAL_CFLAGS += -D_DARWIN_UNLIMITED_STREAMS
endif

LOCAL_CFLAGS += -DSTATIC_ANDROIDFW_FOR_TOOLS

LOCAL_C_INCLUDES += external/libpng
LOCAL_C_INCLUDES += external/zlib

LOCAL_STATIC_LIBRARIES := \
	libandroidfw \
	libutils \
	libcutils \
	libexpat \
	libpng \
	liblog

ifeq ($(HOST_OS),linux)
LOCAL_LDLIBS += -lrt -ldl -lpthread
endif

# Statically link libz for MinGW (Win SDK under Linux),
# and dynamically link for all others.
ifneq ($(strip $(USE_MINGW)),)
  LOCAL_STATIC_LIBRARIES += libz
else
  LOCAL_LDLIBS += -lz
endif

LOCAL_MODULE := aapt

include $(BUILD_HOST_EXECUTABLE)

# aapt for running on the device
# =========================================================
ifneq ($(SDK_ONLY),true)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(aapt_src_files)

LOCAL_MODULE := aapt

LOCAL_C_INCLUDES += bionic
LOCAL_C_INCLUDES += bionic/libstdc++/include
LOCAL_C_INCLUDES += external/stlport/stlport
LOCAL_C_INCLUDES += external/libpng
LOCAL_C_INCLUDES += external/zlib

LOCAL_CFLAGS += -Wno-non-virtual-dtor

LOCAL_SHARED_LIBRARIES := \
        libandroidfw \
        libutils \
        libcutils \
        libpng \
        liblog \
        libz

LOCAL_STATIC_LIBRARIES := \
        libstlport_static \
        libexpat_static

include $(BUILD_EXECUTABLE)
endif

endif # TARGET_BUILD_APPS
