# Copyright (C) 2009 The Android Open Source Project
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
include $(CLEAR_VARS)

# we have the common sources, plus some device-specific stuff
LOCAL_SRC_FILES:= \
	Binder.cpp \
	BpBinder.cpp \
	IInterface.cpp \
	IMemory.cpp \
	IPCThreadState.cpp \
	IPermissionController.cpp \
	IServiceManager.cpp \
	MemoryDealer.cpp \
    MemoryBase.cpp \
    MemoryHeapBase.cpp \
    MemoryHeapPmem.cpp \
	Parcel.cpp \
	ProcessState.cpp \
	Static.cpp

LOCAL_LDLIBS += -lpthread

LOCAL_SHARED_LIBRARIES := \
	liblog \
	libcutils \
	libutils

LOCAL_MODULE:= libbinder

include $(BUILD_SHARED_LIBRARY)
