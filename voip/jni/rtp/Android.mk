#
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
#

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := librtp_jni

LOCAL_SRC_FILES := \
	AudioCodec.cpp \
	AudioGroup.cpp \
	EchoSuppressor.cpp \
	RtpStream.cpp \
	util.cpp \
	rtp_jni.cpp

LOCAL_SRC_FILES += \
	AmrCodec.cpp \
	G711Codec.cpp \
	GsmCodec.cpp

LOCAL_SHARED_LIBRARIES := \
	libnativehelper \
	libcutils \
	libutils \
	libmedia \
	libmedia_native \
	libstagefright_amrnb_common

LOCAL_STATIC_LIBRARIES := libgsm libstagefright_amrnbdec libstagefright_amrnbenc

LOCAL_C_INCLUDES += \
	$(JNI_H_INCLUDE) \
	external/libgsm/inc \
	frameworks/av/media/libstagefright/codecs/amrnb/common/include \
	frameworks/av/media/libstagefright/codecs/amrnb/common/ \
	frameworks/av/media/libstagefright/codecs/amrnb/enc/include \
	frameworks/av/media/libstagefright/codecs/amrnb/enc/src \
	frameworks/av/media/libstagefright/codecs/amrnb/dec/include \
	frameworks/av/media/libstagefright/codecs/amrnb/dec/src \
	$(call include-path-for, audio-effects)

LOCAL_CFLAGS += -fvisibility=hidden



include $(BUILD_SHARED_LIBRARY)
