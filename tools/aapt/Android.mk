# 
# Copyright 2006 The Android Open Source Project
#
# Android Asset Packaging Tool
#

# This tool is prebuilt if we're doing an app-only build.
ifeq ($(TARGET_BUILD_APPS),)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
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
    SourcePos.cpp \
    ZipEntry.cpp \
    ZipFile.cpp


LOCAL_CFLAGS += -Wno-format-y2k
ifeq (darwin,$(HOST_OS))
LOCAL_CFLAGS += -D_DARWIN_UNLIMITED_STREAMS
endif


LOCAL_C_INCLUDES += external/libpng
LOCAL_C_INCLUDES += external/zlib
LOCAL_C_INCLUDES += build/libs/host/include

#LOCAL_WHOLE_STATIC_LIBRARIES := 
LOCAL_STATIC_LIBRARIES := \
	libhost \
	libandroidfw \
	libutils \
	libcutils \
	libexpat \
	libpng

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

endif # TARGET_BUILD_APPS
