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
#include <nativehelper/JNIHelp.h>
#include "android_runtime/AndroidRuntime.h"
#include <media/MediaProfiles.h>

using namespace android;

static Mutex sLock;
MediaProfiles *sProfiles = NULL;

// This function is called from a static block in MediaProfiles.java class,
// which won't run until the first time an instance of this class is used.
static void
android_media_MediaProfiles_native_init(JNIEnv* /* env */)
{
    ALOGV("native_init");
    Mutex::Autolock lock(sLock);

    if (sProfiles == NULL) {
        sProfiles = MediaProfiles::getInstance();
    }
}

static jint
android_media_MediaProfiles_native_get_num_file_formats(JNIEnv* /* env */, jobject /* thiz */)
{
    ALOGV("native_get_num_file_formats");
    return (jint) sProfiles->getOutputFileFormats().size();
}

static jint
android_media_MediaProfiles_native_get_file_format(JNIEnv *env, jobject /* thiz */, jint index)
{
    ALOGV("native_get_file_format: %d", index);
    Vector<output_format> formats = sProfiles->getOutputFileFormats();
    int nSize = formats.size();
    if (index < 0 || index >= nSize) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "out of array boundary");
        return -1;
    }
    return static_cast<jint>(formats[index]);
}

static jint
android_media_MediaProfiles_native_get_num_video_encoders(JNIEnv* /* env */, jobject /* thiz */)
{
    ALOGV("native_get_num_video_encoders");
    return sProfiles->getVideoEncoders().size();
}

static jobject
android_media_MediaProfiles_native_get_video_encoder_cap(JNIEnv *env, jobject /* thiz */,
                                                         jint index)
{
    ALOGV("native_get_video_encoder_cap: %d", index);
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

static jint
android_media_MediaProfiles_native_get_num_audio_encoders(JNIEnv* /* env */, jobject /* thiz */)
{
    ALOGV("native_get_num_audio_encoders");
    return (jint) sProfiles->getAudioEncoders().size();
}

static jobject
android_media_MediaProfiles_native_get_audio_encoder_cap(JNIEnv *env, jobject /* thiz */,
                                                         jint index)
{
    ALOGV("native_get_audio_encoder_cap: %d", index);
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

static bool isCamcorderQualityKnown(int quality)
{
    return ((quality >= CAMCORDER_QUALITY_LIST_START &&
             quality <= CAMCORDER_QUALITY_LIST_END) ||
            (quality >= CAMCORDER_QUALITY_TIME_LAPSE_LIST_START &&
             quality <= CAMCORDER_QUALITY_TIME_LAPSE_LIST_END) ||
             (quality >= CAMCORDER_QUALITY_HIGH_SPEED_LIST_START &&
              quality <= CAMCORDER_QUALITY_HIGH_SPEED_LIST_END));
}

static jobject
android_media_MediaProfiles_native_get_camcorder_profile(JNIEnv *env, jobject /* thiz */, jint id,
                                                         jint quality)
{
    ALOGV("native_get_camcorder_profile: %d %d", id, quality);
    if (!isCamcorderQualityKnown(quality)) {
        jniThrowException(env, "java/lang/RuntimeException", "Unknown camcorder profile quality");
        return NULL;
    }

    camcorder_quality q = static_cast<camcorder_quality>(quality);
    int duration         = sProfiles->getCamcorderProfileParamByName("duration",    id, q);
    int fileFormat       = sProfiles->getCamcorderProfileParamByName("file.format", id, q);
    int videoCodec       = sProfiles->getCamcorderProfileParamByName("vid.codec",   id, q);
    int videoBitRate     = sProfiles->getCamcorderProfileParamByName("vid.bps",     id, q);
    int videoFrameRate   = sProfiles->getCamcorderProfileParamByName("vid.fps",     id, q);
    int videoFrameWidth  = sProfiles->getCamcorderProfileParamByName("vid.width",   id, q);
    int videoFrameHeight = sProfiles->getCamcorderProfileParamByName("vid.height",  id, q);
    int audioCodec       = sProfiles->getCamcorderProfileParamByName("aud.codec",   id, q);
    int audioBitRate     = sProfiles->getCamcorderProfileParamByName("aud.bps",     id, q);
    int audioSampleRate  = sProfiles->getCamcorderProfileParamByName("aud.hz",      id, q);
    int audioChannels    = sProfiles->getCamcorderProfileParamByName("aud.ch",      id, q);

    // Check on the values retrieved
    if (duration == -1 || fileFormat == -1 || videoCodec == -1 || audioCodec == -1 ||
        videoBitRate == -1 || videoFrameRate == -1 || videoFrameWidth == -1 || videoFrameHeight == -1 ||
        audioBitRate == -1 || audioSampleRate == -1 || audioChannels == -1) {

        jniThrowException(env, "java/lang/RuntimeException", "Error retrieving camcorder profile params");
        return NULL;
    }

    jclass camcorderProfileClazz = env->FindClass("android/media/CamcorderProfile");
    jmethodID camcorderProfileConstructorMethodID = env->GetMethodID(camcorderProfileClazz, "<init>", "(IIIIIIIIIIII)V");
    return env->NewObject(camcorderProfileClazz,
                          camcorderProfileConstructorMethodID,
                          duration,
                          quality,
                          fileFormat,
                          videoCodec,
                          videoBitRate,
                          videoFrameRate,
                          videoFrameWidth,
                          videoFrameHeight,
                          audioCodec,
                          audioBitRate,
                          audioSampleRate,
                          audioChannels);
}

static jobject
android_media_MediaProfiles_native_get_camcorder_profiles(JNIEnv *env, jobject /* thiz */, jint id,
                                                          jint quality, jboolean advanced)
{
    ALOGV("native_get_camcorder_profiles: %d %d", id, quality);
    if (!isCamcorderQualityKnown(quality)) {
        jniThrowException(env, "java/lang/RuntimeException", "Unknown camcorder profile quality");
        return NULL;
    }

    camcorder_quality q = static_cast<camcorder_quality>(quality);
    const MediaProfiles::CamcorderProfile *cp = sProfiles->getCamcorderProfile(id, q);
    if (!cp) {
        return NULL;
    }

    int duration         = cp->getDuration();
    int fileFormat       = cp->getFileFormat();

    jclass encoderProfilesClazz = env->FindClass("android/media/EncoderProfiles");
    jmethodID encoderProfilesConstructorMethodID =
        env->GetMethodID(encoderProfilesClazz, "<init>",
                         "(II[Landroid/media/EncoderProfiles$VideoProfile;[Landroid/media/EncoderProfiles$AudioProfile;)V");

    jclass videoProfileClazz = env->FindClass("android/media/EncoderProfiles$VideoProfile");
    jmethodID videoProfileConstructorMethodID =
        env->GetMethodID(videoProfileClazz, "<init>", "(IIIIIIIII)V");

    jclass audioProfileClazz = env->FindClass("android/media/EncoderProfiles$AudioProfile");
    jmethodID audioProfileConstructorMethodID =
        env->GetMethodID(audioProfileClazz, "<init>", "(IIIII)V");

    jobjectArray videoCodecs = (jobjectArray)env->NewObjectArray(
            cp->getVideoCodecs().size(), videoProfileClazz, nullptr);
    {
        int i = 0;
        for (const MediaProfiles::VideoCodec *vc : cp->getVideoCodecs()) {
            chroma_subsampling cs = vc->getChromaSubsampling();
            int bitDepth = vc->getBitDepth();
            hdr_format hdr = vc->getHdrFormat();

            bool isAdvanced =
                (bitDepth != 8 || cs != CHROMA_SUBSAMPLING_YUV_420 || hdr != HDR_FORMAT_NONE);
            if (static_cast<bool>(advanced) && !isAdvanced) {
                continue;
            }

            jobject videoCodec = env->NewObject(videoProfileClazz,
                                                videoProfileConstructorMethodID,
                                                vc->getCodec(),
                                                vc->getFrameWidth(),
                                                vc->getFrameHeight(),
                                                vc->getFrameRate(),
                                                vc->getBitrate(),
                                                vc->getProfile(),
                                                static_cast<int>(cs),
                                                bitDepth,
                                                static_cast<int>(hdr));
            env->SetObjectArrayElement(videoCodecs, i++, videoCodec);
        }
    }

    jobjectArray audioCodecs;
    if (quality >= CAMCORDER_QUALITY_TIME_LAPSE_LIST_START
            && quality <= CAMCORDER_QUALITY_TIME_LAPSE_LIST_END) {
        // timelapse profiles do not have audio codecs
        audioCodecs = (jobjectArray)env->NewObjectArray(0, audioProfileClazz, nullptr);
    } else {
        audioCodecs = (jobjectArray)env->NewObjectArray(
                cp->getAudioCodecs().size(), audioProfileClazz, nullptr);
        int i = 0;
        for (const MediaProfiles::AudioCodec *ac : cp->getAudioCodecs()) {
            jobject audioCodec = env->NewObject(audioProfileClazz,
                                                audioProfileConstructorMethodID,
                                                ac->getCodec(),
                                                ac->getChannels(),
                                                ac->getSampleRate(),
                                                ac->getBitrate(),
                                                ac->getProfile());

            env->SetObjectArrayElement(audioCodecs, i++, audioCodec);
        }
    }

    return env->NewObject(encoderProfilesClazz,
                          encoderProfilesConstructorMethodID,
                          duration,
                          fileFormat,
                          videoCodecs,
                          audioCodecs);
}

static jboolean
android_media_MediaProfiles_native_has_camcorder_profile(JNIEnv* /* env */, jobject /* thiz */,
                                                         jint id, jint quality)
{
    ALOGV("native_has_camcorder_profile: %d %d", id, quality);
    if (!isCamcorderQualityKnown(quality)) {
        return JNI_FALSE;
    }

    camcorder_quality q = static_cast<camcorder_quality>(quality);
    return sProfiles->hasCamcorderProfile(id, q) ? JNI_TRUE : JNI_FALSE;
}

static jint
android_media_MediaProfiles_native_get_num_video_decoders(JNIEnv* /* env */, jobject /* thiz */)
{
    ALOGV("native_get_num_video_decoders");
    return (jint) sProfiles->getVideoDecoders().size();
}

static jint
android_media_MediaProfiles_native_get_video_decoder_type(JNIEnv *env, jobject /* thiz */,
                                                          jint index)
{
    ALOGV("native_get_video_decoder_type: %d", index);
    Vector<video_decoder> decoders = sProfiles->getVideoDecoders();
    int nSize = decoders.size();
    if (index < 0 || index >= nSize) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "out of array boundary");
        return -1;
    }

    return static_cast<jint>(decoders[index]);
}

static jint
android_media_MediaProfiles_native_get_num_audio_decoders(JNIEnv* /* env */, jobject /* thiz */)
{
    ALOGV("native_get_num_audio_decoders");
    return (jint) sProfiles->getAudioDecoders().size();
}

static jint
android_media_MediaProfiles_native_get_audio_decoder_type(JNIEnv *env, jobject /* thiz */,
                                                          jint index)
{
    ALOGV("native_get_audio_decoder_type: %d", index);
    Vector<audio_decoder> decoders = sProfiles->getAudioDecoders();
    int nSize = decoders.size();
    if (index < 0 || index >= nSize) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "out of array boundary");
        return -1;
    }

    return static_cast<jint>(decoders[index]);
}

static jint
android_media_MediaProfiles_native_get_num_image_encoding_quality_levels(JNIEnv* /* env */,
                                                                         jobject /* thiz */,
                                                                         jint cameraId)
{
    ALOGV("native_get_num_image_encoding_quality_levels");
    return (jint) sProfiles->getImageEncodingQualityLevels(cameraId).size();
}

static jint
android_media_MediaProfiles_native_get_image_encoding_quality_level(JNIEnv *env, jobject /* thiz */,
                                                                    jint cameraId, jint index)
{
    ALOGV("native_get_image_encoding_quality_level");
    Vector<int> levels = sProfiles->getImageEncodingQualityLevels(cameraId);
    if (index < 0 || index >= (jint) levels.size()) {
        jniThrowException(env, "java/lang/IllegalArgumentException", "out of array boundary");
        return -1;
    }
    return static_cast<jint>(levels[index]);
}
static const JNINativeMethod gMethodsForEncoderCapabilitiesClass[] = {
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

static const JNINativeMethod gMethodsForCamcorderProfileClass[] = {
    {"native_init",                            "()V",                    (void *)android_media_MediaProfiles_native_init},
    {"native_get_camcorder_profile",           "(II)Landroid/media/CamcorderProfile;",
                                                                         (void *)android_media_MediaProfiles_native_get_camcorder_profile},
    {"native_get_camcorder_profiles",          "(IIZ)Landroid/media/EncoderProfiles;",
                                                                         (void *)android_media_MediaProfiles_native_get_camcorder_profiles},
    {"native_has_camcorder_profile",           "(II)Z",
                                                                         (void *)android_media_MediaProfiles_native_has_camcorder_profile},
};

static const JNINativeMethod gMethodsForDecoderCapabilitiesClass[] = {
    {"native_init",                            "()V",                    (void *)android_media_MediaProfiles_native_init},
    {"native_get_num_video_decoders",          "()I",                    (void *)android_media_MediaProfiles_native_get_num_video_decoders},
    {"native_get_num_audio_decoders",          "()I",                    (void *)android_media_MediaProfiles_native_get_num_audio_decoders},
    {"native_get_video_decoder_type",          "(I)I",                   (void *)android_media_MediaProfiles_native_get_video_decoder_type},
    {"native_get_audio_decoder_type",          "(I)I",                   (void *)android_media_MediaProfiles_native_get_audio_decoder_type},
};

static const JNINativeMethod gMethodsForCameraProfileClass[] = {
    {"native_init",                            "()V",                    (void *)android_media_MediaProfiles_native_init},
    {"native_get_num_image_encoding_quality_levels",
                                               "(I)I",                   (void *)android_media_MediaProfiles_native_get_num_image_encoding_quality_levels},
    {"native_get_image_encoding_quality_level","(II)I",                   (void *)android_media_MediaProfiles_native_get_image_encoding_quality_level},
};

static const char* const kEncoderCapabilitiesClassPathName = "android/media/EncoderCapabilities";
static const char* const kDecoderCapabilitiesClassPathName = "android/media/DecoderCapabilities";
static const char* const kCamcorderProfileClassPathName = "android/media/CamcorderProfile";
static const char* const kCameraProfileClassPathName = "android/media/CameraProfile";

// This function only registers the native methods, and is called from
// JNI_OnLoad in android_media_MediaPlayer.cpp
int register_android_media_MediaProfiles(JNIEnv *env)
{
    int ret1 = AndroidRuntime::registerNativeMethods(env,
               kEncoderCapabilitiesClassPathName,
               gMethodsForEncoderCapabilitiesClass,
               NELEM(gMethodsForEncoderCapabilitiesClass));

    int ret2 = AndroidRuntime::registerNativeMethods(env,
               kCamcorderProfileClassPathName,
               gMethodsForCamcorderProfileClass,
               NELEM(gMethodsForCamcorderProfileClass));

    int ret3 = AndroidRuntime::registerNativeMethods(env,
               kDecoderCapabilitiesClassPathName,
               gMethodsForDecoderCapabilitiesClass,
               NELEM(gMethodsForDecoderCapabilitiesClass));

    int ret4 = AndroidRuntime::registerNativeMethods(env,
               kCameraProfileClassPathName,
               gMethodsForCameraProfileClass,
               NELEM(gMethodsForCameraProfileClass));

    // Success if all return values from above are 0
    return (ret1 || ret2 || ret3 || ret4);
}
