#
# Copyright (C) 2014 The Android Open Source Project
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
#

local_target_dir := $(TARGET_OUT_DATA)/local/tmp
LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_CFLAGS += -DUSE_OPENGL_RENDERER -DEGL_EGLEXT_PROTOTYPES -DGL_GLEXT_PROTOTYPES
LOCAL_CFLAGS += -Wno-unused-parameter
LOCAL_CFLAGS += -DATRACE_TAG=ATRACE_TAG_VIEW -DLOG_TAG=\"OpenGLRenderer\"

LOCAL_SRC_FILES:= \
	TestContext.cpp \
	main.cpp

LOCAL_C_INCLUDES += \
	$(LOCAL_PATH)/.. \
	external/skia/src/core

LOCAL_SHARED_LIBRARIES := \
	liblog \
	libcutils \
	libutils \
	libskia \
	libgui \
	libui \
	libhwui

ifeq ($(WITH_MALLOC_LEAK_CHECK),true)
	LOCAL_CFLAGS += -DMALLOC_LEAK_CHECK
endif

LOCAL_MODULE_PATH := $(local_target_dir)
LOCAL_MODULE:= hwuitest
LOCAL_MODULE_TAGS := tests
LOCAL_MULTILIB := both
LOCAL_MODULE_STEM_32 := hwuitest
LOCAL_MODULE_STEM_64 := hwuitest64

include external/stlport/libstlport.mk
include $(BUILD_EXECUTABLE)

include $(call all-makefiles-under,$(LOCAL_PATH))
