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
    $(TOP)/frameworks/base/include/media/stagefright/openmax \
    $(TOP)/frameworks/base/core/jni/mediaeditor \
    $(TOP)/frameworks/media/libvideoeditor/vss/inc \
    $(TOP)/frameworks/media/libvideoeditor/vss/common/inc \
    $(TOP)/frameworks/media/libvideoeditor/vss/mcs/inc \
    $(TOP)/frameworks/media/libvideoeditor/vss/stagefrightshells/inc \
    $(TOP)/frameworks/media/libvideoeditor/include \
    $(TOP)/frameworks/media/libvideoeditor/lvpp \
    $(TOP)/frameworks/media/libvideoeditor/osal/inc

LOCAL_SHARED_LIBRARIES := \
    libaudioutils \
    libcutils \
    libdl \
    libutils \
    libandroid_runtime \
    libnativehelper \
    libmedia \
    libaudioflinger \
    libbinder \
    libstagefright \
    libstagefright_omx \
    libgui \
    libvideoeditorplayer


LOCAL_CFLAGS += \
    -DUSE_STAGEFRIGHT_CODECS \
    -DUSE_STAGEFRIGHT_AUDIODEC \
    -DUSE_STAGEFRIGHT_VIDEODEC \
    -DUSE_STAGEFRIGHT_AUDIOENC \
    -DUSE_STAGEFRIGHT_VIDEOENC \
    -DUSE_STAGEFRIGHT_READERS \
    -DUSE_STAGEFRIGHT_3GPP_READER

LOCAL_STATIC_LIBRARIES := \
    libvideoeditor_core \
    libstagefright_color_conversion \
    libvideoeditor_3gpwriter \
    libvideoeditor_mcs \
    libvideoeditor_videofilters \
    libvideoeditor_stagefrightshells \
    libvideoeditor_osal

LOCAL_MODULE:= libvideoeditor_jni

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
