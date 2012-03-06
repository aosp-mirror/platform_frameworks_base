# Copyright (C) 2010 The Android Open Source Project
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
LOCAL_MODULE_TAGS := tests
LOCAL_MODULE:= libhwcTest
LOCAL_SRC_FILES:= hwcTestLib.cpp
LOCAL_C_INCLUDES += system/extras/tests/include \
    bionic \
    bionic/libstdc++/include \
    external/stlport/stlport \
	$(call include-path-for, opengl-tests-includes)

LOCAL_CFLAGS := -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

LOCAL_SHARED_LIBRARIES += libcutils libutils libstlport
LOCAL_STATIC_LIBRARIES += libglTest


include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_SRC_FILES:= hwcStress.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libEGL \
    libGLESv2 \
    libui \
    libhardware \

LOCAL_STATIC_LIBRARIES := \
    libtestUtil \
    libglTest \
    libhwcTest \

LOCAL_C_INCLUDES += \
    system/extras/tests/include \
    hardware/libhardware/include \
	$(call include-path-for, opengl-tests-includes)

LOCAL_CFLAGS := -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

LOCAL_MODULE:= hwcStress
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/nativestresstest

LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS := -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

include $(BUILD_NATIVE_TEST)

include $(CLEAR_VARS)
LOCAL_SRC_FILES:= hwcRects.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libEGL \
    libGLESv2 \
    libui \
    libhardware \

LOCAL_STATIC_LIBRARIES := \
    libtestUtil \
    libglTest \
    libhwcTest \

LOCAL_C_INCLUDES += \
    system/extras/tests/include \
    hardware/libhardware/include \
	$(call include-path-for, opengl-tests-includes)

LOCAL_MODULE:= hwcRects
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/nativeutil

LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS := -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

include $(BUILD_NATIVE_TEST)

include $(CLEAR_VARS)
LOCAL_SRC_FILES:= hwcColorEquiv.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libEGL \
    libGLESv2 \
    libui \
    libhardware \

LOCAL_STATIC_LIBRARIES := \
    libtestUtil \
    libglTest \
    libhwcTest \

LOCAL_C_INCLUDES += \
    system/extras/tests/include \
    hardware/libhardware/include \
	$(call include-path-for, opengl-tests-includes)

LOCAL_MODULE:= hwcColorEquiv
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/nativeutil

LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS := -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

include $(BUILD_NATIVE_TEST)

include $(CLEAR_VARS)
LOCAL_SRC_FILES:= hwcCommit.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libEGL \
    libGLESv2 \
    libui \
    libhardware \

LOCAL_STATIC_LIBRARIES := \
    libtestUtil \
    libglTest \
    libhwcTest \

LOCAL_C_INCLUDES += \
    system/extras/tests/include \
    hardware/libhardware/include \
	$(call include-path-for, opengl-tests-includes)

LOCAL_MODULE:= hwcCommit
LOCAL_MODULE_PATH := $(TARGET_OUT_DATA)/nativebenchmark

LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS := -DGL_GLEXT_PROTOTYPES -DEGL_EGLEXT_PROTOTYPES

include $(BUILD_NATIVE_TEST)
