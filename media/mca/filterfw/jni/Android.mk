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
# Build module libfilterfw_jni
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_MODULE    = libfilterfw_jni

LOCAL_SRC_FILES = jni_init.cpp \
                  jni_gl_environment.cpp \
                  jni_gl_frame.cpp \
                  jni_native_buffer.cpp \
                  jni_native_frame.cpp \
                  jni_native_program.cpp \
                  jni_shader_program.cpp \
                  jni_util.cpp \
                  jni_vertex_frame.cpp

# Need FilterFW lib
include $(LOCAL_PATH)/../native/libfilterfw.mk

# Also need the JNI headers.
LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    $(LOCAL_PATH)/..

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code -Wno-unused-parameter

LOCAL_SHARED_LIBRARIES := libmedia libgui libandroid

include $(BUILD_STATIC_LIBRARY)
