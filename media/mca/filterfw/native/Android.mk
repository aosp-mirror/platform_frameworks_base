# Copyright (C) 2011 The Android Open Source Project
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

LOCAL_PATH := $(call my-dir)

#####################
# Build module libfilterfw_static

include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_MODULE := libfilterfw_native

LOCAL_SRC_FILES += core/geometry.cpp \
                   core/gl_env.cpp \
                   core/gl_frame.cpp \
                   core/native_frame.cpp \
                   core/native_program.cpp \
                   core/shader_program.cpp \
                   core/vertex_frame.cpp \
                   core/value.cpp

# add local includes
include $(LOCAL_PATH)/libfilterfw.mk

# gcc should always be placed at the end.
LOCAL_EXPORT_LDLIBS := -llog -lgcc

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

LOCAL_STATIC_LIBRARIES := \
    libarect \

LOCAL_SHARED_LIBRARIES += \
    libgui \

# TODO: Build a shared library as well?
include $(BUILD_STATIC_LIBRARY)

