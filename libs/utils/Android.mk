# Copyright (C) 2008 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH:= $(call my-dir)

# libutils is a little unique: It's built twice, once for the host
# and once for the device.

commonSources:= \
	Asset.cpp \
	AssetDir.cpp \
	AssetManager.cpp \
	BasicHashtable.cpp \
	BlobCache.cpp \
	BufferedTextOutput.cpp \
	CallStack.cpp \
	Debug.cpp \
	FileMap.cpp \
	Flattenable.cpp \
	LinearTransform.cpp \
	ObbFile.cpp \
	PropertyMap.cpp \
	RefBase.cpp \
	ResourceTypes.cpp \
	SharedBuffer.cpp \
	Static.cpp \
	StopWatch.cpp \
	StreamingZipInflater.cpp \
	String8.cpp \
	String16.cpp \
	StringArray.cpp \
	SystemClock.cpp \
	TextOutput.cpp \
	Threads.cpp \
	Timers.cpp \
	Tokenizer.cpp \
	Unicode.cpp \
	VectorImpl.cpp \
	ZipFileCRO.cpp \
	ZipFileRO.cpp \
	ZipUtils.cpp \
	misc.cpp


# For the host
# =====================================================

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= $(commonSources)

LOCAL_MODULE:= libutils

LOCAL_CFLAGS += -DLIBUTILS_NATIVE=1 $(TOOL_CFLAGS)
LOCAL_C_INCLUDES += external/zlib

ifeq ($(HOST_OS),windows)
ifeq ($(strip $(USE_CYGWIN),),)
# Under MinGW, ctype.h doesn't need multi-byte support
LOCAL_CFLAGS += -DMB_CUR_MAX=1
endif
endif

ifeq ($(TARGET_OS),linux)
LOCAL_LDLIBS += -lrt -ldl
endif

include $(BUILD_HOST_STATIC_LIBRARY)



# For the device
# =====================================================
include $(CLEAR_VARS)


# we have the common sources, plus some device-specific stuff
LOCAL_SRC_FILES:= \
	$(commonSources) \
	BackupData.cpp \
	BackupHelpers.cpp \
	Looper.cpp

ifeq ($(TARGET_OS),linux)
LOCAL_LDLIBS += -lrt -ldl
endif

LOCAL_C_INCLUDES += \
		external/zlib \
		external/icu4c/common

LOCAL_LDLIBS += -lpthread

LOCAL_SHARED_LIBRARIES := \
	libz \
	liblog \
	libcutils \
	libdl \
	libcorkscrew

LOCAL_MODULE:= libutils
include $(BUILD_SHARED_LIBRARY)

ifeq ($(TARGET_OS),linux)
include $(CLEAR_VARS)
LOCAL_C_INCLUDES += external/zlib external/icu4c/common
LOCAL_LDLIBS := -lrt -ldl -lpthread
LOCAL_MODULE := libutils
LOCAL_SRC_FILES := $(commonSources) BackupData.cpp BackupHelpers.cpp
include $(BUILD_STATIC_LIBRARY)
endif


# Include subdirectory makefiles
# ============================================================

# If we're building with ONE_SHOT_MAKEFILE (mm, mmm), then what the framework
# team really wants is to build the stuff defined by this makefile.
ifeq (,$(ONE_SHOT_MAKEFILE))
include $(call first-makefiles-under,$(LOCAL_PATH))
endif
