#
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

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    VideoEditorMain.cpp \
    VideoEditorClasses.cpp \
    VideoEditorOsal.cpp \
    VideoEditorJava.cpp \
    VideoEditorPropertiesMain.cpp \
    VideoEditorThumbnailMain.cpp  \
    VideoBrowserMain.c

LOCAL_C_INCLUDES += \
    $(TOP)/frameworks/base/core/jni \
    $(TOP)/frameworks/base/include \
    $(TOP)/frameworks/base/include/media \
    $(TOP)/frameworks/base/media/libmediaplayerservice \
    $(TOP)/frameworks/base/media/libstagefright \
    $(TOP)/frameworks/base/media/libstagefright/include \
    $(TOP)/frameworks/base/media/libstagefright/rtsp \
    $(JNI_H_INCLUDE) \
    $(call include-path-for, corecg graphics) \
    $(TOP)/frameworks/native/include/media/editor \
    $(TOP)/frameworks/base/core/jni/mediaeditor \
    $(TOP)/frameworks/av/libvideoeditor/vss/inc \
    $(TOP)/frameworks/av/libvideoeditor/vss/common/inc \
    $(TOP)/frameworks/av/libvideoeditor/vss/mcs/inc \
    $(TOP)/frameworks/av/libvideoeditor/vss/stagefrightshells/inc \
    $(TOP)/frameworks/av/libvideoeditor/lvpp \
    $(TOP)/frameworks/av/libvideoeditor/osal/inc \
    $(TOP)/frameworks/native/include/media/openmax

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libaudioflinger \
    libaudioutils \
    libbinder \
    libcutils \
    liblog \
    libdl \
    libgui \
    libmedia \
    libnativehelper \
    libstagefright \
    libstagefright_foundation \
    libstagefright_omx \
    libutils \
    libvideoeditor_core \
    libvideoeditor_osal \
    libvideoeditor_videofilters \
    libvideoeditorplayer \


LOCAL_CFLAGS += \
    -DUSE_STAGEFRIGHT_CODECS \
    -DUSE_STAGEFRIGHT_AUDIODEC \
    -DUSE_STAGEFRIGHT_VIDEODEC \
    -DUSE_STAGEFRIGHT_AUDIOENC \
    -DUSE_STAGEFRIGHT_VIDEOENC \
    -DUSE_STAGEFRIGHT_READERS \
    -DUSE_STAGEFRIGHT_3GPP_READER

LOCAL_MODULE:= libvideoeditor_jni

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
