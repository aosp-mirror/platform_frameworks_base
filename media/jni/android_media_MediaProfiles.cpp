/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

//#define LOG_NDEBUG 0
#define LOG_TAG "MediaProfilesJNI"
#include <utils/Log.h>

#include <stdio.h>
#include <utils/threads.h>

#include "jni.h"
#include "JNIHelp.h"
#include "android_runtime/AndroidRuntime.h"
#include <media/MediaProfiles.h>

using namespace android;

static Mutex sLock;
MediaProfiles *sProfiles = NULL;

// This function is called from a static block in MediaProfiles.java class,
// which won't run until the first time an instance of this class is used.
static void
android_media_MediaProfiles_native_init(JNIEnv *env)
{
    LOGV("native_init");
    Mutex::Autolock lock(sLock);

    if (sProfiles == NULL) {
        sProfiles = MediaProfiles::getInstance();
    }
}

static int
android_media_MediaProfiles_native_get_num_file_formats(JNIEnv *env, jobject thiz)
{
    LOGV("native_get_num_file_formats");
    return sProfiles->getOutputFileFormats().size();
}

static int
android_media_MediaProfiles_native_get_file_format(JNIEnv *env, jobject thiz, jint index)
{
    LOGV("native_get_file_format: %d", index);
    Vector<output_format> formats = sProfiles->getOutputFileFormats();
    int nSize = formats.size();
    if (index < 0 || index >= nSize) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "out of array boundary");
        return -1;
    }
    int format = static_cast<int>(formats[index]);
    return format;
}

static int
android_media_MediaProfiles_native_get_num_video_encoders(JNIEnv *env, jobject thiz)
{
    LOGV("native_get_num_video_encoders");
    return sProfiles->getVideoEncoders().size();
}

static jobject
android_media_MediaProfiles_native_get_video_encoder_cap(JNIEnv *env, jobject thiz, jint index)
{
    LOGV("native_get_video_encoder_cap: %d", index);
    Vector<video_encoder> encoders = sProfiles->getVideoEncoders();
    int nSize = encoders.size();
    if (index < 0 || index >= nSize) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "out of array boundary");
        return NULL;
    }

    video_encoder encoder = encoders[index];
    int minBitRate = sProfiles->getVideoEncoderParamByName("enc.vid.bps.min", encoder);
    int maxBitRate = sProfiles->getVideoEncoderParamByName("enc.vid.bps.max", encoder);
    int minFrameRate = sProfiles->getVideoEncoderParamByName("enc.vid.fps.min", encoder);
    int maxFrameRate = sProfiles->getVideoEncoderParamByName("enc.vid.fps.max", encoder);
    int minFrameWidth = sProfiles->getVideoEncoderParamByName("enc.vid.width.min", encoder);
    int maxFrameWidth = sProfiles->getVideoEncoderParamByName("enc.vid.width.max", encoder);
    int minFrameHeight = sProfiles->getVideoEncoderParamByName("enc.vid.height.min", encoder);
    int maxFrameHeight = sProfiles->getVideoEncoderParamByName("enc.vid.height.max", encoder);

    // Check on the values retrieved
    if ((minBitRate == -1 || maxBitRate == -1) ||
        (minFrameRate == -1 || maxFrameRate == -1) ||
        (minFrameWidth == -1 || maxFrameWidth == -1) ||
        (minFrameHeight == -1 || maxFrameHeight == -1)) {

        jniThrowException(env, "java/lang/RuntimeException", "Error retrieving video encoder capability params");
        return NULL;
    }

    // Construct an instance of the VideoEncoderCap and set its member variables
    jclass videoEncoderCapClazz = env->FindClass("android/media/EncoderCapabilities$VideoEncoderCap");
    jmethodID videoEncoderCapConstructorMethodID = env->GetMethodID(videoEncoderCapClazz, "<init>", "(IIIIIIIII)V");
    jobject cap = env->NewObject(videoEncoderCapClazz,
                                 videoEncoderCapConstructorMethodID,
                                 static_cast<int>(encoder),
                                 minBitRate, maxBitRate,
                                 minFrameRate, maxFrameRate,
                                 minFrameWidth, maxFrameWidth,
                                 minFrameHeight, maxFrameHeight);
    return cap;
}

static int
android_media_MediaProfiles_native_get_num_audio_encoders(JNIEnv *env, jobject thiz)
{
    LOGV("native_get_num_audio_encoders");
    return sProfiles->getAudioEncoders().size();
}

static jobject
android_media_MediaProfiles_native_get_audio_encoder_cap(JNIEnv *env, jobject thiz, jint index)
{
    LOGV("native_get_audio_encoder_cap: %d", index);
    Vector<audio_encoder> encoders = sProfiles->getAudioEncoders();
    int nSize = encoders.size();
    if (index < 0 || index >= nSize) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "out of array boundary");
        return NULL;
    }

    audio_encoder encoder = encoders[index];
    int minBitRate = sProfiles->getAudioEncoderParamByName("enc.aud.bps.min", encoder);
    int maxBitRate = sProfiles->getAudioEncoderParamByName("enc.aud.bps.max", encoder);
    int minSampleRate = sProfiles->getAudioEncoderParamByName("enc.aud.hz.min", encoder);
    int maxSampleRate = sProfiles->getAudioEncoderParamByName("enc.aud.hz.max", encoder);
    int minChannels = sProfiles->getAudioEncoderParamByName("enc.aud.ch.min", encoder);
    int maxChannels = sProfiles->getAudioEncoderParamByName("enc.aud.ch.max", encoder);

    // Check on the values retrieved
    if ((minBitRate == -1 || maxBitRate == -1) ||
        (minSampleRate == -1 || maxSampleRate == -1) ||
        (minChannels == -1 || maxChannels == -1)) {

        jniThrowException(env, "java/lang/RuntimeException", "Error retrieving video encoder capability params");
        return NULL;
    }

    jclass audioEncoderCapClazz = env->FindClass("android/media/EncoderCapabilities$AudioEncoderCap");
    jmethodID audioEncoderCapConstructorMethodID = env->GetMethodID(audioEncoderCapClazz, "<init>", "(IIIIIII)V");
    jobject cap = env->NewObject(audioEncoderCapClazz,
                                 audioEncoderCapConstructorMethodID,
                                 static_cast<int>(encoder),
                                 minBitRate, maxBitRate,
                                 minSampleRate, maxSampleRate,
                                 minChannels, maxChannels);
    return cap;
}

static JNINativeMethod gMethods[] = {
    {"native_init",                            "()V",                    (void *)android_media_MediaProfiles_native_init},
    {"native_get_num_file_formats",            "()I",                    (void *)android_media_MediaProfiles_native_get_num_file_formats},
    {"native_get_file_format",                 "(I)I",                   (void *)android_media_MediaProfiles_native_get_file_format},
    {"native_get_num_video_encoders",          "()I",                    (void *)android_media_MediaProfiles_native_get_num_video_encoders},
    {"native_get_num_audio_encoders",          "()I",                    (void *)android_media_MediaProfiles_native_get_num_audio_encoders},

    {"native_get_video_encoder_cap",           "(I)Landroid/media/EncoderCapabilities$VideoEncoderCap;",
                                                                         (void *)android_media_MediaProfiles_native_get_video_encoder_cap},

    {"native_get_audio_encoder_cap",           "(I)Landroid/media/EncoderCapabilities$AudioEncoderCap;",
                                                                         (void *)android_media_MediaProfiles_native_get_audio_encoder_cap},
};

static const char* const kClassPathName = "android/media/MediaProfiles";

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaProfiles(JNIEnv *env)
{
    return AndroidRuntime::registerNativeMethods(env,
                "android/media/EncoderCapabilities", gMethods, NELEM(gMethods));
}
