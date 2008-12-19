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
	BufferedTextOutput.cpp \
	CallStack.cpp \
	Debug.cpp \
	FileMap.cpp \
	RefBase.cpp \
	ResourceTypes.cpp \
	SharedBuffer.cpp \
	Static.cpp \
	StopWatch.cpp \
	String8.cpp \
	String16.cpp \
	SystemClock.cpp \
	TextOutput.cpp \
	Threads.cpp \
	TimerProbe.cpp \
	Timers.cpp \
	VectorImpl.cpp \
    ZipFileCRO.cpp \
	ZipFileRO.cpp \
	ZipUtils.cpp \
	misc.cpp \
	ported.cpp \
	LogSocket.cpp

#
# The cpp files listed here do not belong in the device
# build.  Consult with the swetland before even thinking about
# putting them in commonSources.
#
# They're used by the simulator runtime and by host-side tools like
# aapt and the simulator front-end.
#
hostSources:= \
	InetAddress.cpp \
	Pipe.cpp \
	Socket.cpp \
	ZipEntry.cpp \
	ZipFile.cpp

# For the host
# =====================================================

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= $(commonSources) $(hostSources)

ifeq ($(HOST_OS),linux)
# Use the futex based mutex and condition variable
# implementation from android-arm because it's shared mem safe
	LOCAL_SRC_FILES += \
		futex_synchro.c \
		executablepath_linux.cpp
endif
ifeq ($(HOST_OS),darwin)
	LOCAL_SRC_FILES += \
		executablepath_darwin.cpp
endif

LOCAL_MODULE:= libutils

LOCAL_CFLAGS += -DLIBUTILS_NATIVE=1 $(TOOL_CFLAGS)
LOCAL_C_INCLUDES += external/zlib

ifeq ($(HOST_OS),windows)
ifeq ($(strip $(USE_CYGWIN),),)
# Under MinGW, ctype.h doesn't need multi-byte support
LOCAL_CFLAGS += -DMB_CUR_MAX=1
endif
endif

include $(BUILD_HOST_STATIC_LIBRARY)



# For the device
# =====================================================
include $(CLEAR_VARS)


# we have the common sources, plus some device-specific stuff
LOCAL_SRC_FILES:= \
	$(commonSources) \
	Binder.cpp \
	BpBinder.cpp \
	IInterface.cpp \
	IMemory.cpp \
	IPCThreadState.cpp \
	MemoryDealer.cpp \
    MemoryBase.cpp \
    MemoryHeapBase.cpp \
    MemoryHeapPmem.cpp \
	Parcel.cpp \
	ProcessState.cpp \
	IPermissionController.cpp \
	IServiceManager.cpp \
	Unicode.cpp

ifeq ($(TARGET_SIMULATOR),true)
LOCAL_SRC_FILES += $(hostSources)
endif

ifeq ($(TARGET_OS),linux)
# Use the futex based mutex and condition variable
# implementation from android-arm because it's shared mem safe
LOCAL_SRC_FILES += futex_synchro.c
LOCAL_LDLIBS += -lrt -ldl
endif

LOCAL_C_INCLUDES += \
		external/zlib \
		external/icu4c/common
LOCAL_LDLIBS += -lpthread

LOCAL_SHARED_LIBRARIES := \
	libz \
	liblog \
	libcutils

ifneq ($(TARGET_SIMULATOR),true)
ifeq ($(TARGET_OS)-$(TARGET_ARCH),linux-x86)
# This is needed on x86 to bring in dl_iterate_phdr for CallStack.cpp
LOCAL_SHARED_LIBRARIES += \
	libdl
endif # linux-x86
endif # sim

LOCAL_MODULE:= libutils

#LOCAL_CFLAGS+=
#LOCAL_LDFLAGS:=

include $(BUILD_SHARED_LIBRARY)

