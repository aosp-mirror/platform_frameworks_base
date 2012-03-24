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

LOCAL_SRC_FILES := \
        omx_jpeg_decoder.cpp \
        jpeg_decoder_bench.cpp \
        SkOmxPixelRef.cpp \
        StreamSource.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libskia \
    libstagefright \
    libstagefright_foundation \
    libbinder \
    libutils \
    libjpeg

LOCAL_C_INCLUDES := \
    $(TOP)/external/jpeg \
    $(TOP)/external/skia/include/config \
    $(TOP)/external/skia/include/core \
    $(TOP)/external/skia/include/images \
    $(TOP)/external/skia/include/utils \
    $(TOP)/external/skia/include/effects \
    $(TOP)/frameworks/base/media/libstagefright \
    $(TOP)/frameworks/base/include/ \
    $(TOP)/frameworks/base/ \
    $(TOP)/frameworks/native/include/media/openmax

LOCAL_MODULE := jpeg_bench

LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)
